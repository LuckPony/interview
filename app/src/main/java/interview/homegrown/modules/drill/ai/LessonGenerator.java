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
import java.util.regex.Pattern;

/**
 * 先教后考生成器（用户 2026-08-20 决策：练习前先「教」再「考」）。
 *
 * <p>解决两件事：
 * <ol>
 *   <li><b>拆解</b>：一个 concept（如「Python 基础语法」）往往包含多个子知识点，
 *       一整段讲解会讲不全。故先把 concept 拆成若干个子知识点（数量由内容决定、
 *       不凑数、语义不重复，{@link #decompose}），
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
     * <p>只认「重开话术 + 随后的顶格编号小节」两个信号同时出现才算重开，
     * 普通讲解里的编号列表（含代码块里的编号注释）不会被误判截断。
     */
    private static final int RESTART_DETECT_AFTER_CHARS = 400;
    /** 讲解硬上限：远大于提示词要求的 200-400 字，只在模型失控无限输出时兜底（按行边界截断）。 */
    private static final int MAX_LESSON_CHARS = 20000;
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
            1. 数量由知识点本身的内容决定，不强求凑数：内容少的知识点拆 2-3 个即可，
               内容丰富、层级复杂的知识点可以多拆（没有数量上限）；但绝不为了凑数量
               硬拆出凑数子点，也不允许出现内容重复或几乎一样的子点。
            2. 每个子知识点必须与其他子知识点**内容明显不同、互不重叠**：如果两个候选子点
               讲的是同一件事（哪怕说法不同），只保留一个；任何两个子点的讲解重点不得相近、
               不得互为子集、不得只是换个说法。
            3. 每个子知识点是一个精炼的名词短语（8-18 字），不要编号、不要解释、不要 Markdown
            4. 子知识点要覆盖这个概念的主要核心内容，顺序按「从基础到进阶」排列
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
            8. 如果 prompt 中给出了同一知识点下「其他子知识点已讲解」的内容，讲解必须聚焦当前子知识点
               独有的部分：不得照搬或复述其他子点讲过的核心论断、讲解结构或例子，必要时换一组不同的例子与比喻；
               即使没有给出其他子点的讲解，也要默认相邻子点讲的是相关面，做到自我区分，别把讲解写成
               覆盖整个大概念、与其他子点重迭的泛泛综述
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
                    .collect(java.util.stream.Collectors.collectingAndThen(
                            java.util.stream.Collectors.toList(), LessonGenerator::dedupeSimilar));
        } catch (Exception e) {
            log.warn("知识点拆解失败 (concept={}): {}", concept.getId(), e.getMessage());
            return List.of();
        }
    }

    /**
     * 语义去重：拆解模型有时会输出「内容几乎一样、只是说法不同」的子知识点
     * （如「列表推导式」与「列表推导式的用法」）。这里用字符二元组 Jaccard 相似度
     * + 包含关系过滤近似重复项，保留第一个。公开供新增子知识点时做重复校验。
     */
    public static List<String> dedupeSimilar(List<String> items) {
        List<String> result = new java.util.ArrayList<>();
        for (String raw : items) {
            String cur = raw.trim();
            if (cur.isEmpty()) continue;
            boolean dup = false;
            for (String kept : result) {
                if (similar(cur, kept)) { dup = true; break; }
            }
            if (!dup) result.add(cur);
        }
        return result;
    }

    private static boolean similar(String a, String b) {
        String ka = normKey(a), kb = normKey(b);
        if (ka.isEmpty() || kb.isEmpty()) return false;
        if (jaccardBigrams(ka, kb) >= 0.72) return true;
        String shorter = ka.length() <= kb.length() ? ka : kb;
        String longer = ka.length() <= kb.length() ? kb : ka;
        // 短词 >= 4 字符且被长词完整包含：视为重复（避免误伤「排序/堆排序」这类粒度差异）
        return shorter.length() >= 4 && longer.contains(shorter);
    }

    /** 归一化比较键：小写、去空白与常见标点。 */
    private static String normKey(String s) {
        return s.toLowerCase().replaceAll("[\\s，。、；：（）()【】\\[\\]·~`!！?？,.;:\"'']", "");
    }

    /** 字符二元组 Jaccard 相似度。 */
    private static double jaccardBigrams(String a, String b) {
        java.util.Set<String> ga = bigrams(a), gb = bigrams(b);
        if (ga.isEmpty() || gb.isEmpty()) return 0;
        java.util.Set<String> inter = new java.util.HashSet<>(ga);
        inter.retainAll(gb);
        java.util.Set<String> union = new java.util.HashSet<>(ga);
        union.addAll(gb);
        return (double) inter.size() / union.size();
    }

    private static java.util.Set<String> bigrams(String s) {
        java.util.Set<String> set = new java.util.HashSet<>();
        if (s.length() < 2) {
            set.add(s);
            return set;
        }
        for (int i = 0; i < s.length() - 1; i++) {
            set.add(s.substring(i, i + 2));
        }
        return set;
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
        return streamLesson(concept, subPoint, context, null, List.of(), onToken, onReasoning);
    }

    /**
     * 「换种描述」重讲：previousText 非空时，在 prompt 里附上旧讲解并要求换角度/换描述重新讲，
     * 避免生成与上次几乎相同的文本；生成逻辑与普通讲解完全一致。
     */
    public String streamLesson(Concept concept, String subPoint, String context, String previousText,
                               Consumer<String> onToken, Consumer<String> onReasoning) {
        return streamLesson(concept, subPoint, context, previousText, List.of(), onToken, onReasoning);
    }

    /** 同概念下一个兄弟子知识点的已生成讲解摘要（用于让新讲解避重）。 */
    public record SiblingLesson(String subPoint, String summary) {}

    /** 从同概念下已缓存的兄弟子点讲解中提取摘要（排除当前子点），供新讲解避重。 */
    public List<SiblingLesson> siblingSummaries(List<interview.homegrown.modules.drill.domain.ConceptLesson> existing,
                                                String excludeSubPoint) {
        if (existing == null || existing.isEmpty()) return List.of();
        List<SiblingLesson> out = new java.util.ArrayList<>();
        for (var c : existing) {
            if (excludeSubPoint != null && excludeSubPoint.equals(c.getSubPoint())) continue;
            String txt = c.getLessonText();
            if (txt == null || txt.isBlank()) continue;
            out.add(new SiblingLesson(c.getSubPoint(), truncate(txt, 140)));
        }
        return out;
    }

    /**
     * 流式讲解单个子知识点（带避重上下文）：{@code siblings} 为同概念下其他子点已生成讲解的摘要，
     * 通过 user prompt 注入，要求本讲解只讲本子点独有的部分、避免与兄弟点重复（含例子/结构）。
     */
    public String streamLesson(Concept concept, String subPoint, String context, String previousText,
                               List<SiblingLesson> siblings,
                               Consumer<String> onToken, Consumer<String> onReasoning) {
        StringBuilder user = new StringBuilder(String.format("""
                概念：%s（主题：%s，认知层 L%d）
                子知识点：%s

                学习上下文（学生进度 / 概念要点 / 用户上传资料 / 互联网补充）：
                %s
                """, concept.getName(), concept.getTopic(), concept.getLayer(),
                subPoint, contextBlock(context)));

        StringBuilder siblingBlock = new StringBuilder();
        if (siblings != null && !siblings.isEmpty()) {
            siblingBlock.append("""

                    同一知识点下，下面这些兄弟子知识点已经讲解过（你的讲解**必须避免与它们重复**，
                    包括核心论断、讲解结构、例子；只讲当前子知识点独有的部分，例子请换一组不同的）：
                    """);
            for (SiblingLesson s : siblings) {
                if (s == null || s.subPoint() == null || s.summary() == null) continue;
                siblingBlock.append("- 「").append(s.subPoint()).append("」：").append(s.summary()).append('\n');
            }
            user.append(siblingBlock);
        }

        if (previousText != null && !previousText.isBlank()) {
            user.append("""

                    用户觉得上次讲解不够清楚，点「换种描述」要求重新讲一遍。请务必做到：
                    - 换一个讲解角度、换一种组织方式、换一组例子或比喻，不要照搬上次的结构与措辞
                    - 内容保持准确，仍然覆盖这个子知识点
                    - 上一次的讲解（只作参考避免重复，不要重复它，也不要展示给用户）：
                    """).append(truncate(previousText, 1500));
        }
        user.append("\n\n请讲解这个子知识点。");
        final String userPrompt = user.toString();

        StringBuilder buf = new StringBuilder();
        boolean[] stopped = {false};
        rawClient.stream(LESSON_SYSTEM, userPrompt,
                token -> {
                    if (stopped[0]) return;
                    String candidate = buf + token;
                    int cut = findRestartIndex(candidate);
                    if (cut < 0 && candidate.length() > MAX_LESSON_CHARS) cut = capIndex(candidate, MAX_LESSON_CHARS);
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

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max) + "…";
    }

    /** 清理旧缓存中已经存在的“讲完后从头再讲”尾段。 */
    public String normalizeLesson(String text) {
        if (text == null || text.isBlank()) return "";
        int cut = findRestartIndex(text);
        if (cut < 0 && text.length() > MAX_LESSON_CHARS) cut = capIndex(text, MAX_LESSON_CHARS);
        return (cut >= 0 ? text.substring(0, cut) : text).trim();
    }

    /**
     * 检测“讲解已收尾后从头再讲”的重复尾段起点；找不到返回 -1。
     * <p>只认明确的「重开」信号，且必须满足全部条件才判定，避免把正常讲解误截：
     * <ul>
     *   <li>信号出现在 {@value #RESTART_DETECT_AFTER_CHARS} 字之后（讲解已进入正文）；</li>
     *   <li>信号行不在代码围栏（``` ... ```）内；</li>
     *   <li>信号行前面是空行（独立段落，而不是正文里的过渡句）；</li>
     *   <li>「好的，我们开始上课/学习/讲解」话术，且随后 3 行内出现顶格编号小节
     *       （行首顶格、无缩进的 “1.” / “1、”），确认是重开而不是“开始讲解第二个要点”之类的过渡。</li>
     * </ul>
     * 普通讲解里的编号列表（核心要点、例子、常见误区各一组）不会命中：它们不在空行后的
     * 「重开话术」之后，且代码围栏内的编号注释被直接跳过。
     */
    private static int findRestartIndex(String text) {
        if (text == null || text.length() < RESTART_DETECT_AFTER_CHARS) return -1;

        boolean inFence = false;
        boolean prevBlank = true; // 首行视为“前面有空行”
        int lineStart = 0;
        int n = text.length();
        while (lineStart < n) {
            int lineEnd = text.indexOf('\n', lineStart);
            if (lineEnd < 0) lineEnd = n;
            String line = text.substring(lineStart, lineEnd);
            String trimmed = line.trim();

            if (trimmed.startsWith("```")) {
                inFence = !inFence;
                prevBlank = trimmed.isEmpty();
                lineStart = lineEnd + 1;
                continue;
            }
            if (!inFence && lineStart >= RESTART_DETECT_AFTER_CHARS
                    && prevBlank && RESTART_INTRO.matcher(line).find()
                    && hasTopLevelSectionWithin(text, lineEnd + 1, 3)) {
                return lineStart;
            }
            prevBlank = trimmed.isEmpty();
            lineStart = lineEnd + 1;
        }
        return -1;
    }

    /** 从 pos 起往下最多 maxLines 行内，是否出现顶格编号小节（行首无缩进的 “1.” / “1、”）。 */
    private static boolean hasTopLevelSectionWithin(String text, int pos, int maxLines) {
        int lineStart = pos;
        int n = text.length();
        boolean inFence = false;
        for (int i = 0; i < maxLines && lineStart < n; i++) {
            int lineEnd = text.indexOf('\n', lineStart);
            if (lineEnd < 0) lineEnd = n;
            String line = text.substring(lineStart, lineEnd);
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                inFence = !inFence;
                lineStart = lineEnd + 1;
                continue;
            }
            if (!inFence && (trimmed.startsWith("1.") || trimmed.startsWith("1、"))
                    && line.length() > 0 && line.charAt(0) != ' ' && line.charAt(0) != '\t') {
                return true;
            }
            lineStart = lineEnd + 1;
        }
        return false;
    }

    /** 硬上限截断点：取 max 之前最后一个换行（按行边界截断，不把一句话劈成两半）；找不到则用 max。 */
    private static int capIndex(String text, int max) {
        int nl = text.lastIndexOf('\n', max);
        return nl > 0 ? nl : max;
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
