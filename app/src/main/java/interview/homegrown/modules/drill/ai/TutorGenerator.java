package interview.homegrown.modules.drill.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.homegrown.common.ai.LlmRawClient;
import interview.homegrown.modules.drill.domain.DrillTurn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 教学讲解生成器（用户 2026-08-10 决策）。
 *
 * <p>判分报告（逐点 HIT/PARTIAL/MISS + 分数档位）只是诊断，
 * 但学生真正需要的是<b>为什么这样设计、为什么这个答案是错的、应该怎么理解这道题</b>。
 * 这个生成器把"判分"升级为"讲解"：基于 stem + 评分点 + 判分结果 + 学生答案，
 * 让 LLM 以老师口吻给学生讲明白这道题。
 *
 * <p>决策权仍在服务端：
 * <ul>
 *   <li>讲解 200-400 字，不列分数不复述判分点；</li>
 *   <li>与 GraderText 串联：判分落库后由 {@code /drill/{runId}/tutor-stream} SSE 端点异步调用
 *       {@link #streamExplain}，逐 token 推送给客户端，最后写回 drill_turn.tutor_text；</li>
 *   <li>失败时静默返回 null，不阻塞判分提交。</li>
 * </ul>
 */
@Component
public class TutorGenerator {

    private static final Logger log = LoggerFactory.getLogger(TutorGenerator.class);

    private final LlmRawClient rawClient;
    private final ObjectMapper objectMapper;

    public TutorGenerator(LlmRawClient rawClient, ObjectMapper objectMapper) {
        this.rawClient = rawClient;
        this.objectMapper = objectMapper;
    }

    /** 与 streamExplain 共用的 prompt，避免重复维护 */
    private static final String SYSTEM_PROMPT = """
            你是一位耐心的技术老师，一对一辅导学生。基于题目、评分点、判分结果和学生答案，讲明白这道题的核心知识点。
            要求：
            1. 200-400 字中文，像站在白板前讲课；只讲“为什么这样是对的、学生答案的关键得失、该怎么理解”，不列分数、不评判“答得好不好”。
            2. 涉及程序行为/API/配置/数据结构/实现方式时，给出最小可运行代码（围栏代码块、注明语言）；涉及链路/生命周期/状态流转时给 mermaid 图。给任何代码前先在心里 trace 一遍：这段代码在「单线程直接调用」时会不会阻塞/死锁、在「并发调用」时是否真能互斥；发现隐患就改用正确写法（如带缓冲 channel、sync.Mutex），绝不给出在某种调用方式下会死锁却号称“标准答案”的代码。
            3. 准确性：只讲确切技术事实；不确定就明说“这点我不完全确定，建议用 xxx 验证”，绝不编造。
            4. 用 Markdown 排版（加粗关键点、必要时列表/小标题）。
            5. 不用中文破折号；段落间一个空行；结尾是一句完整的话。
            """;

    /**
     * 对话式辅导 system prompt：判分前的多轮对话。
     *
     * <p>默认<b>苏格拉底式引导</b>：不直接给答案，帮学生自己把题想明白（主动回忆）；
     * 只有学生显式点击「看答案」按钮时才揭晓并切换到 {@link #CHAT_REVEAL_SYSTEM_PROMPT}。
     * 普通聊天文本绝不用于自动猜测揭示意图，这里也<b>永远不自行泄底</b>，
     * 否则「结束并评分」会算到照抄答案的轮次，量化分数失真。
     */
    private static final String CHAT_SYSTEM_PROMPT = """
            你是一位耐心的技术辅导老师，一对一辅导学生做题（苏格拉底引导式）。目标是帮学生自己想明白；评分要点里题面未要求的扩展内容不得泄底、不得据此判回答不完整。
            1. 准确性 + 纠错：只讲确切技术事实，绝不编造。当学生指出你的回答、代码或所谓“标准答案”有误时立刻核查：若他正确，直接承认“这里我错了”并给修正，绝不复读错误结论、绝不把学生的正确质疑说成“需要补的地方”。给任何代码前先在心里 trace 一遍：这段代码在「单线程直接调用」时会不会阻塞/死锁、在「并发调用」时是否真能互斥；发现隐患就改用正确写法（如带缓冲 channel、sync.Mutex），绝不给出在某种调用方式下会死锁却号称“标准答案”的代码。
            2. 不泄完整答案：学生索要完整答案/代码时，先给最关键结论缓解卡点，再提示点「看答案」按钮，不要直接倾倒完整解法。
            3. 苏格拉底引导（先诊断、再解释、后引导）：学生开始实质作答后，先判断哪个评分点答错或没答到。
            若答错了或没答上来——先明确指出哪里错/哪里没答到，解释为什么（给正确的原理/结论，必要时给最小反例），
            让他明白问题所在，然后抛一个引导问题或请他修正，最后问一句「现在理解了吗？可以试着修正一下」。
            学生卡住或明显不会——给关键提示并解释思路，引导他自己迈出下一步，同样以询问是否理解收尾。
            不要机械地重复提问，更不要只反问不给解释；每轮要么推进理解、要么推进作答。
            4. 答得完整且正确→肯定并提示可结束；就具体点提问→一句话结论+简短解释；确认答案对不对→明确说对/部分对/错并解释原因。
            5. 学生答错/没答上来时，必须给原因+指出错处+询问是否理解；仅在「讨论已收束、学生已掌握」时才允许收尾提示「结束并评分」。
            6. 100-300 字、白话+最小示例、Markdown 排版、不用中文破折号、不提分数判分、结尾完整句。
            """;

    /**
     * 揭示答案模式：学生已明确索要答案/提示（点「看答案」按钮或自然语言被服务端识别），
     * 直接给完整讲解；此时<b>不再引导追问</b>。
     *
     * <p>按进度揭示（用户决策）：只看「当前小问」的答案，不一次倒出全部。
     * 当前小问 = 对话历史里最近一个由老师提出、学生尚未完整答完的问题。
     */
    private static final String CHAT_REVEAL_SYSTEM_PROMPT = """
            你是一位耐心的技术辅导老师，一对一辅导学生做题。学生已明确想看答案，先判断当前正在讨论的小问（最近一个你提出、学生未答完的问题），然后：
            1. 完整回答他索要的内容；要完整代码就给可运行代码，给之前先在心里 trace 一遍（单线程直接调用 vs 并发调用，会不会死锁/崩溃），发现隐患就改正确写法。
            2. 只讲透当前小问，由浅入深，专业术语补白话；更早已理解的小问一句话带过，不一次倒出全部无关答案。
            3. 学生之前有作答时，简要指出哪里对、哪里要修正。
            4. 给完答案不追问新问题、不问“理解了吗”；明确说参考答案已给出，以“你可以继续提问，或点击下方「结束并评分」。”收尾，只能由学生点击结束。
            5. 150-500 字（代码不计）、不用中文破折号、不提分数判分、Markdown 排版、结尾完整句。
            """;

    /**
     * 同步版讲解：调 LLM 一次性拿完整文本。失败返回 null。
     * 当前已不再被 GradingService/RehearsalService 调用——保留供单测或批量补写场景。
     */
    public String explain(String stem, String pointsJson, String byConceptJson, String rawAnswer) {
        return streamExplain(stem, pointsJson, byConceptJson, rawAnswer, token -> {});
    }

    /**
     * 流式版讲解：逐 token 回调 onToken；最终累积完整文本返回（失败/空为 null）。
     * onReasoning 可选：收到模型思考内容（reasoning_content）时独立回调，供前端展示"思考过程"。
     * context 可选：学习上下文（学生进度/概念要点/资料块/互联网补充），讲解时作为依据。
     */
    public String streamExplain(String stem, String pointsJson, String byConceptJson,
                                String rawAnswer, java.util.function.Consumer<String> onToken) {
        return streamExplain(stem, pointsJson, byConceptJson, rawAnswer, onToken, null);
    }

    public String streamExplain(String stem, String pointsJson, String byConceptJson,
                                String rawAnswer, java.util.function.Consumer<String> onToken,
                                java.util.function.Consumer<String> onReasoning) {
        return streamExplain(stem, pointsJson, byConceptJson, rawAnswer, null, onToken, onReasoning);
    }

    public String streamExplain(String stem, String pointsJson, String byConceptJson,
                                String rawAnswer, String context,
                                java.util.function.Consumer<String> onToken,
                                java.util.function.Consumer<String> onReasoning) {
        String pointsText = formatPoints(pointsJson);
        String verdictsText = formatVerdicts(byConceptJson);
        String contextBlock = (context == null || context.isBlank())
                ? ""
                : "\n\n学习上下文（学生进度 / 概念要点 / 用户资料 / 互联网补充，讲解时作为依据，引用资料内容时可注明出处）：\n" + context;
        String user = String.format("""
                题目：
                %s

                评分点：
                %s

                判分要点：
                %s

                学生的答案：
                %s
                %s

                请讲解这道题。
                """, stem, pointsText, verdictsText, rawAnswer == null ? "（未作答）" : rawAnswer,
                contextBlock);

        StringBuilder buf = new StringBuilder();
        // 面向用户的教学讲解禁用 reasoning_content 回退（避免把内部思考混进正文）；
        // 思考通过 onReasoning 独立推送，正文走 onToken。
        rawClient.stream(SYSTEM_PROMPT, user,
                token -> {
                    buf.append(token);
                    try {
                        onToken.accept(token);
                    } catch (Exception e) {
                        log.debug("tutor onToken 回调异常（已吞）: {}", e.getMessage());
                    }
                },
                err -> log.warn("教学讲解流式生成失败: {}", err.getMessage()),
                /* fallbackToReasoning */ false,
                onReasoning == null ? null : r -> {
                    try {
                        onReasoning.accept(r);
                    } catch (Exception ignored) {
                    }
                });
        String text = buf.toString().trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * 对话式辅导流式生成：判分前的多轮对话，AI 扮演辅导老师与学生交流。
     * <p>
     * 与 {@link #streamExplain} 的区别：
     * <ul>
     *   <li>无判分结果（byConceptJson）—— 此时尚未判分</li>
     *   <li>包含完整对话历史（之前几轮的问答）</li>
     *   <li>主问之后逐条追问：学生弄懂当前小问且表明理解后，才基于上下文出下一个小问
     *       （封顶 {@link #MAX_FOLLOWUPS} 个）</li>
     * </ul>
     *
     * @param stem        题干
     * @param pointsJson  评分点 JSON（给 AI 知道"应该答什么"，不暴露给学生）
     * @param turns       全部对话轮次（按 round 升序），最后一轮的 rawAnswer 是学生最新消息，
     *                    tutorText 为 null（尚未生成）
     * @param onToken     逐 token 回调
     * @return 完整回复文本（失败/空为 null）
     */
    public String streamChat(String stem, String pointsJson, List<DrillTurn> turns,
                             java.util.function.Consumer<String> onToken) {
        return streamChat(stem, pointsJson, turns, null, null, null, onToken, null, false);
    }

    /**
     * 对话式辅导流式生成：判分前的多轮对话，AI 扮演辅导老师与学生交流。
     * <p>
     * 与 {@link #streamExplain} 的区别：
     * <ul>
     *   <li>无判分结果（byConceptJson）—— 此时尚未判分</li>
     *   <li>包含完整对话历史（之前几轮的问答）</li>
     *   <li>默认<b>引导思考而非直接讲解答案</b>；reveal=true 时才给出完整答案（揭示边界）</li>
     * </ul>
     *
     * @param stem        题干
     * @param pointsJson  评分点 JSON（给 AI 知道"应该答什么"，不暴露给学生）
     * @param turns       全部对话轮次（按 round 升序），最后一轮的 rawAnswer 是学生最新消息，
     *                    tutorText 为 null（尚未生成）
     * @param onToken     逐 token 回调
     * @param onReasoning 思考内容回调（可空）
     * @param reveal      学生已明确索要答案 → 用揭示答案 prompt 给出完整讲解（默认 false）
     * @return 完整回复文本（失败/空为 null）
     */
    public String streamChat(String stem, String pointsJson, List<DrillTurn> turns,
                             java.util.function.Consumer<String> onToken,
                             java.util.function.Consumer<String> onReasoning,
                             boolean reveal) {
        return streamChat(stem, pointsJson, turns, null, null, null, onToken, onReasoning, reveal);
    }

    /**
     * 带学习上下文（学生进度/概念要点/资料块/互联网补充）的完整版。
     * 上下文仅作参考素材：追问可结合资料细节，也可用通用知识；引用资料内容时注明出处（C3）。
     */
    public String streamChat(String stem, String pointsJson, List<DrillTurn> turns, String context,
                             java.util.function.Consumer<String> onToken,
                             java.util.function.Consumer<String> onReasoning,
                             boolean reveal) {
        return streamChat(stem, pointsJson, turns, context, null, null, onToken, onReasoning, reveal);
    }

    /**
     * 带学习上下文 + 学生消息附带的图片（data URL 列表；仅视觉模型，null/空则纯文本）。
     *
     * @param guideHint 苏格拉底 judge 判 needs_guide 时给的本轮引导点（可空）。
     *                  注入 user prompt，AI 以此展开：先指出错处/解释原因，再引导。
     */
    public String streamChat(String stem, String pointsJson, List<DrillTurn> turns, String context,
                             List<String> images, String guideHint,
                             java.util.function.Consumer<String> onToken,
                             java.util.function.Consumer<String> onReasoning,
                             boolean reveal) {
        String pointsText = formatPoints(pointsJson);

        // 对话历史保留最近 12 轮；代码作答（含 ``` 围栏）不截断，其余宽松截断到 1000 字符。
        // 之前的 500 截断会把长代码砍成残句，AI 看到的作答顺序与内容都不对。
        StringBuilder history = new StringBuilder();
        int from = Math.max(0, turns.size() - 12);
        for (int i = from; i < turns.size(); i++) {
            DrillTurn t = turns.get(i);
            String answer = t.getRawAnswer();
            if (answer != null && !answer.isBlank()) {
                history.append("学生: ").append(trimForContext(answer)).append("\n\n");
            }
            String tutor = t.getTutorText();
            if (tutor != null && !tutor.isBlank()) {
                history.append("老师: ").append(trimForContext(tutor)).append("\n\n");
            }
        }

        String contextBlock = (context == null || context.isBlank())
                ? ""
                : "\n\n学习上下文（学生进度 / 概念要点 / 用户资料 / 互联网补充，作为参考素材，引用时可注明出处）：\n" + context;
        String guideBlock = (guideHint == null || guideHint.isBlank())
                ? ""
                : "\n\n【本轮判定：学生作答未达标，最需要引导的点】\n" + guideHint
                + "\n请围绕这个点：先指出他哪里没答对/没答到并解释原因（给正确原理或最小反例），"
                + "再抛一个引导问题或请他修正，最后明确问一句「现在理解了吗？」并邀请他试着修正。";
        String user = String.format("""
                题目：
                %s

                评分要点（仅供核对主问；题目正文未要求的旧题扩展评分点一律忽略，不得据此认定回答不完整）：
                %s

                对话历史：
                %s
                %s
                %s

                学生最新消息附带了 %d 张图片（截图，随消息一起发送），请结合图片内容回应：
                图片是学生作答的截图/报错/代码/题目时，请基于图里的实际内容回应，不要臆测图里没有的东西。
                请回复学生的最新消息。
                """, stem, pointsText,
                history.toString().isBlank() ? "（无）" : history.toString(), contextBlock, guideBlock,
                images == null ? 0 : images.size());

        StringBuilder buf = new StringBuilder();
        // 模式选择：reveal 优先（答案已揭示 → 完整讲解，不再追问）；否则走苏格拉底引导（CHAT_SYSTEM_PROMPT）。
        String system = reveal ? CHAT_REVEAL_SYSTEM_PROMPT : CHAT_SYSTEM_PROMPT;
        rawClient.stream(system, user, images,
                token -> {
                    buf.append(token);
                    try {
                        onToken.accept(token);
                    } catch (Exception e) {
                        log.debug("chat onToken 回调异常（已吞）: {}", e.getMessage());
                    }
                },
                err -> log.warn("对话式辅导流式生成失败: {}", err.getMessage()),
                /* fallbackToReasoning */ false,
                onReasoning == null ? null : r -> {
                    try {
                        onReasoning.accept(r);
                    } catch (Exception ignored) {
                    }
                });
        String text = buf.toString().trim();
        return text.isEmpty() ? null : text;
    }

    /** 供对话历史用的裁剪：代码作答（含 ``` 围栏）完整保留；其余文本宽松截断到 1000 字符。 */
    private static String trimForContext(String s) {
        if (s == null || s.length() <= 1000) return s == null ? "" : s;
        if (s.contains("```")) return s;   // 代码作答不截断，保证 AI 看到完整代码
        return s.substring(0, 1000) + "…";
    }

    /** 把评分点 JSON 展平成纯文本，避免模型被 JSON 格式干扰 */
    private String formatPoints(String pointsJson) {
        if (pointsJson == null || pointsJson.isBlank()) return "（无）";
        try {
            JsonNode root = objectMapper.readTree(pointsJson);
            List<String> out = new ArrayList<>();
            walkPoints(root, "", out);
            return out.isEmpty() ? pointsJson : String.join("；", out);
        } catch (Exception e) {
            return pointsJson;
        }
    }

    private void walkPoints(JsonNode node, String prefix, List<String> out) {
        if (node == null) return;
        if (node.isArray()) {
            for (JsonNode child : node) walkPoints(child, prefix, out);
            return;
        }
        if (node.isObject()) {
            if (node.has("points")) {
                walkPoints(node.get("points"), prefix, out);
                return;
            }
            if (node.has("text")) {
                out.add(node.get("text").asText());
                return;
            }
            // 兜底：把对象的标量字段拼出来
            node.fields().forEachRemaining(e -> {
                if (e.getValue().isValueNode()) out.add(prefix + e.getValue().asText());
            });
        }
    }

    /** 把判分要点（pointResults 的 evidence / verdict）整理为简短文本 */
    private String formatVerdicts(String byConceptJson) {
        if (byConceptJson == null || byConceptJson.isBlank()) return "（无）";
        try {
            JsonNode root = objectMapper.readTree(byConceptJson);
            if (!root.isArray()) return byConceptJson;
            List<String> out = new ArrayList<>();
            for (JsonNode concept : root) {
                JsonNode prs = concept.path("pointResults");
                if (!prs.isArray()) continue;
                for (JsonNode p : prs) {
                    String verdict = p.path("verdict").asText("");
                    String point = p.path("point").asText("");
                    String ev = p.path("evidence").asText("");
                    if (!point.isEmpty()) {
                        String line = "[" + verdict + "] " + point;
                        if (!ev.isEmpty() && ev.length() < 200) line += " — " + ev;
                        out.add(line);
                    }
                }
            }
            return out.isEmpty() ? byConceptJson : String.join("；", out);
        } catch (Exception e) {
            return byConceptJson;
        }
    }

    /** 超长文本截断（保留开头），避免单条消息把对话上下文撑爆 */
    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max) + "…（截断）";
    }
}