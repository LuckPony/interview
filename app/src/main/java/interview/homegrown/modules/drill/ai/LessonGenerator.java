package interview.homegrown.modules.drill.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.homegrown.common.ai.LlmRawClient;
import interview.homegrown.common.ai.StructuredOutputInvoker;
import interview.homegrown.modules.drill.domain.Concept;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 先教后考生成器（用户 2026-08-20 决策：练习前先「教」再「考」）。
 *
 * <p>解决两件事：
 * <ol>
 *   <li><b>拆解</b>：一个 concept（如「Python 基础语法」）往往包含多个子知识点，
 *       一整段讲解会讲不全。故先把 concept 拆成 3-8 个子知识点（{@link #decompose}），
 *       结果缓存在 {@code concept.lesson_outline}。</li>
 *   <li><b>逐子点讲解</b>：对单个子知识点流式生成一段 200-400 字的聚焦讲解（{@link #streamLesson}），
 *       结果按 (concept_id, sub_point) 缓存在 {@code concept_lesson} 表。</li>
 * </ol>
 */
@Component
public class LessonGenerator {

    private static final Logger log = LoggerFactory.getLogger(LessonGenerator.class);

    /** 子知识点名上限（与 concept_lesson.sub_point VARCHAR(300) 对齐，留足安全余量）。 */
    private static final int MAX_SUB_POINT_CHARS = 200;
    /** 讲解注入的学习上下文上限（防止超长资料块把 prompt 撑爆）。 */
    private static final int MAX_CONTEXT_CHARS = 8000;

    /**
     * 模型偶尔在一篇讲解已经收尾后再次说“开始上课”，并从第 1 节重新生成。
     * 该重复正文如果进入 concept_lesson 缓存，之后每次打开都会重复展示。
     */
    private static final int RESTART_DETECT_AFTER_CHARS = 400;
    private static final int MAX_LESSON_CHARS = 5000;
    private static final Pattern SECTION_ONE = Pattern.compile(
            "(?m)^\\s*(?:#{1,4}\\s*)?(?:\\*\\*)?1[.、]\\s*[^\\n]+");
    private static final Pattern RESTART_INTRO = Pattern.compile(
            "(?m)^\\s*(?:好的|好)[，,。！!\\s]*(?:我们)?(?:现在)?开始(?:上课|学习|讲解)");

    private final StructuredOutputInvoker invoker;
    private final LlmRawClient rawClient;
    private final ObjectMapper objectMapper;

    public LessonGenerator(StructuredOutputInvoker invoker, LlmRawClient rawClient,
                           ObjectMapper objectMapper) {
        this.invoker = invoker;
        this.rawClient = rawClient;
        this.objectMapper = objectMapper;
    }

    /** 拆解输出的结构化载体（BeanOutputConverter 按 JSON 反序列化）。 */
    public static class Outline {
        public List<String> subPoints;
    }

    private static final String DECOMPOSE_SYSTEM = """
            你是一位经验丰富的技术老师，正在为一位学习者规划一个知识点的学习路径。
            你的任务是把一个知识点拆解成若干个子知识点，方便「先逐个讲解、再逐个考察」。
            要求：
            1. 拆成 3-8 个子知识点，粒度要细到「能在一段 200-400 字的讲解里讲透一个」
            2. 每个子知识点是一个精炼的名词短语（10-20 字），不要编号、不要解释、不要 Markdown
            3. 子知识点要覆盖这个概念的全部主要内容，彼此不重叠
            4. 顺序按「从基础到进阶」排列
            5. 只输出 JSON，严格遵循格式说明
            """;

    private static final String LESSON_SYSTEM = """
            你是一位耐心、有经验的技术老师，正在一对一辅导一位零基础学习者。
            你的任务是把一个子知识点讲明白，让学习者从「完全不懂」到「能上手、能做题」。
            要求：
            1. 200-400 字中文，像站在白板前讲课，从最基础讲起，假设学习者对这个子知识点一无所知
            2. 讲清楚：这个子知识点是什么、核心要点（分点列出）、一个最小可运行/贴近工程的例子
            3. 例子必须具体。只要涉及程序行为、API、配置、数据结构或实现方式，就必须给出最小代码/配置，
               不能只用文字描述；代码必须用围栏代码块（```语言 ... ```，原样保留缩进与换行）。
               涉及调用链、生命周期、架构、状态流转或图片式关系时，再给出一个 ```mermaid 图，
               使用 flowchart 或 sequenceDiagram，节点文字保持简短，让前端直接渲染成示意图
            4. 优先结合学习上下文（用户上传资料 / 互联网补充）讲解，引用资料内容时可注明出处；
               资料没覆盖的部分用通用知识讲，不得编造资料或互联网内容里没有的事实
            5. 不要使用中文破折号（——），改用逗号或句号
            6. 用 Markdown 排版：适当用小标题/列表，段落之间用一个空行分隔，保持紧凑
            7. 结尾必须是一句完整的话
            """;

    /** 拆解概念为子知识点清单；失败返回空列表（调用方降级为「整概念讲解」）。 */
    public List<String> decompose(Concept concept, String context) {
        try {
            String user = String.format("""
                    知识点：%s（主题：%s，认知层 L%d）
                    %s

                    学习上下文（学生进度 / 概念要点 / 用户上传资料 / 互联网补充，作为拆解依据，
                    资料覆盖到的子点优先保留）：
                    %s

                    请把这个知识点拆解成子知识点清单。
                    """, concept.getName(), concept.getTopic(), concept.getLayer(),
                    desc(concept), contextBlock(context));
            Outline o = invoker.invoke(DECOMPOSE_SYSTEM, user, Outline.class);
            if (o == null || o.subPoints == null || o.subPoints.isEmpty()) return List.of();
            return o.subPoints.stream()
                    .map(this::cleanSubPoint)
                    .filter(s -> !s.isEmpty())
                    .distinct()
                    .toList();
        } catch (Exception e) {
            log.warn("知识点拆解失败 (concept={}): {}", concept.getId(), e.getMessage());
            return List.of();
        }
    }

    /** 序列化子知识点清单（缓存进 concept.lesson_outline）。 */
    public String outlineToJson(List<String> subPoints) {
        try {
            return objectMapper.writeValueAsString(subPoints);
        } catch (Exception e) {
            return null;
        }
    }

    /** 反序列化缓存的子知识点清单。 */
    public List<String> outlineFromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<String> list = objectMapper.readValue(json, new TypeReference<List<String>>() {});
            return list.stream().map(this::cleanSubPoint).filter(s -> !s.isEmpty()).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 流式讲解单个子知识点：逐 token 回调 onToken，最终累积完整文本返回（失败/空为 null）。
     * onReasoning 可选：收到模型思考内容时独立回调，供前端展示「思考过程」。
     */
    public String streamLesson(Concept concept, String subPoint, String context,
                               Consumer<String> onToken, Consumer<String> onReasoning) {
        String user = String.format("""
                概念：%s（主题：%s，认知层 L%d）
                子知识点：%s

                学习上下文（学生进度 / 概念要点 / 用户上传资料 / 互联网补充）：
                %s

                请讲解这个子知识点。
                """, concept.getName(), concept.getTopic(), concept.getLayer(),
                subPoint, contextBlock(context));

        StringBuilder buf = new StringBuilder();
        boolean[] stopped = {false};
        rawClient.stream(LESSON_SYSTEM, user,
                token -> {
                    if (stopped[0]) return;
                    String candidate = buf + token;
                    int cut = findRestartIndex(candidate);
                    if (cut < 0 && candidate.length() > MAX_LESSON_CHARS) cut = MAX_LESSON_CHARS;
                    int acceptedEnd = cut >= 0 ? Math.max(buf.length(), cut) : candidate.length();
                    String accepted = candidate.substring(buf.length(), acceptedEnd);
                    if (!accepted.isEmpty()) {
                        buf.append(accepted);
                        try {
                            onToken.accept(accepted);
                        } catch (Exception e) {
                            log.debug("lesson onToken 回调异常（已吞）: {}", e.getMessage());
                        }
                    }
                    if (cut >= 0) {
                        stopped[0] = true;
                        log.debug("检测到讲解重复开头或异常超长，已截断 (concept={}, subPoint={})",
                                concept.getId(), subPoint);
                    }
                },
                err -> log.warn("子知识点讲解流式生成失败: {}", err.getMessage()),
                /* fallbackToReasoning */ false,
                onReasoning == null ? null : r -> {
                    try {
                        onReasoning.accept(r);
                    } catch (Exception ignored) {
                    }
                });
        String text = normalizeLesson(buf.toString());
        return text.isEmpty() ? null : text;
    }

    /** 清理旧缓存中已经存在的“讲完后从头再讲”尾段。 */
    public String normalizeLesson(String text) {
        if (text == null || text.isBlank()) return "";
        int cut = findRestartIndex(text);
        if (cut < 0 && text.length() > MAX_LESSON_CHARS) cut = MAX_LESSON_CHARS;
        return (cut >= 0 ? text.substring(0, cut) : text).trim();
    }

    private static int findRestartIndex(String text) {
        if (text == null || text.length() < RESTART_DETECT_AFTER_CHARS) return -1;

        Matcher sections = SECTION_ONE.matcher(text);
        int sectionCount = 0;
        while (sections.find()) {
            sectionCount++;
            if (sectionCount >= 2 && sections.start() >= RESTART_DETECT_AFTER_CHARS) {
                return sections.start();
            }
        }

        Matcher intro = RESTART_INTRO.matcher(text);
        while (intro.find()) {
            if (intro.start() >= RESTART_DETECT_AFTER_CHARS) return intro.start();
        }
        return -1;
    }

    private String cleanSubPoint(String s) {
        if (s == null) return "";
        String t = s.trim();
        // 去掉模型可能加上的编号前缀（"1." / "1、" / "- " / "· "）
        t = t.replaceFirst("^\\d+[.、)]\\s*", "").replaceFirst("^[-·•]\\s*", "").trim();
        if (t.length() > MAX_SUB_POINT_CHARS) t = t.substring(0, MAX_SUB_POINT_CHARS);
        return t;
    }

    private String desc(Concept c) {
        String d = c.getDescription();
        return (d == null || d.isBlank()) ? "" : "说明：" + d;
    }

    private String contextBlock(String context) {
        if (context == null || context.isBlank()) return "（无，用通用知识讲解）";
        if (context.length() > MAX_CONTEXT_CHARS) return context.substring(0, MAX_CONTEXT_CHARS) + "…（截断）";
        return context;
    }
}
