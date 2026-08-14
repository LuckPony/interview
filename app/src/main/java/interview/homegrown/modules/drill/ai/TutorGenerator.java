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
            你是一位耐心、有经验的技术老师，正在一对一辅导学生。
            你的任务是基于题目、评分点、判分结果和学生答案，给学生讲明白这道题的核心知识点。
            要求：
            1. 200-400 字中文，像站在白板前讲课，不要列举分数或复述"判分通过/未中"
            2. 解释这道题在考什么、为什么这样设计/实现是对的、学生答案的关键得失、应该怎么理解
            3. 不评判学生"答得好不好"，专注"讲明白"
            4. 不要使用中文破折号（——/-），改用逗号或句号
            5. 用 Markdown 排版增强可读性：关键术语加粗、可适当用 ### 小标题、列表；
               涉及代码时【必须】用围栏代码块（以 ```语言 开头、``` 结尾，代码原样保留缩进与换行），
               严禁把代码写成普通段落、行内代码或缩进代码，否则代码会被渲染成混乱的一行
            6. 段落之间用一个空行分隔，不要多个连续换行；保持紧凑，不要为了凑字数堆砌空行
            7. 结尾必须是一句完整的话；禁止以"所以""因此""可见""总之"等连接词/总结词开头戛然而止——如果用了这种词，必须把句子写完整再收尾
            """;

    /**
     * 对话式辅导 system prompt：判分前的多轮对话。
     *
     * <p>默认<b>苏格拉底式引导</b>：不直接给答案，帮学生自己把题想明白（主动回忆）；
     * 只有学生明确索要答案/提示时才揭晓（由服务端 {@code AnswerRevealDetector} 或「看答案」
     * 按钮决定并切换到 {@link #CHAT_REVEAL_SYSTEM_PROMPT}，这里<b>永远不自行泄底</b>，
     * 否则「结束并评分」会算到照抄答案的轮次，量化分数失真。
     */
    private static final String CHAT_SYSTEM_PROMPT = """
            你是一位耐心的技术辅导老师，正在一对一辅导学生做一道技术题。
            你的目标是帮学生<b>自己</b>把这道题想明白，而不是替他想。
            1. 学生回答正确且完整：先肯定，再点出一个可以深化的方向或追问一个问题，仍不要直接给出标准答案
            2. 学生回答有误或不完整：指出哪里不对、缺了什么，然后用引导性提问或小提示帮他修正，
               让他自己得出答案；严禁直接给出完整正确答案、标准实现或完整解题步骤
            3. 学生只是确认自己的答案对不对：不要揭晓标准答案，只告诉他是对、方向对但还差一点、
               还是不对，然后继续引导
            4. 学生明确索要答案或提示（如“告诉我答案”“答案是什么”）：告诉他可以点输入框旁的
               「看答案」按钮获取完整讲解，不要在这里直接给出答案
            5. 回复 100-200 字中文，像聊天一样自然
            6. 不要使用中文破折号（——/-），改用逗号或句号
            7. 不要提及评分、分数、判分、得分等概念
            8. 可以用 Markdown 排版（加粗关键点、必要时用列表）；
               涉及代码时用围栏代码块（```语言 ... ```，代码原样保留缩进与换行），不要写成普通段落
            9. 结尾必须是一句完整的话
            """;

    /**
     * 揭示答案模式：学生已明确索要答案/提示（点「看答案」按钮或自然语言被服务端识别），
     * 直接给完整讲解。与 {@link #CHAT_SYSTEM_PROMPT} 互补，由服务端显式选择。
     */
    private static final String CHAT_REVEAL_SYSTEM_PROMPT = """
            你是一位耐心的技术辅导老师，正在一对一辅导学生做一道技术题。
            学生已经明确表示想看答案或提示，请直接给出：
            1. 这道题的正确思路和完整答案（或关键步骤），讲清楚为什么这样设计/实现
            2. 如学生之前有作答，简要指出哪里对、哪里需要修正
            3. 回复 150-300 字中文，像聊天一样自然
            4. 不要使用中文破折号（——/-），改用逗号或句号
            5. 不要提及评分、分数、判分、得分等概念
            6. 可以用 Markdown 排版（加粗关键点、必要时用列表）；
               涉及代码时用围栏代码块（```语言 ... ```，代码原样保留缩进与换行），不要写成普通段落
            7. 结尾必须是一句完整的话
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
     */
    public String streamExplain(String stem, String pointsJson, String byConceptJson,
                                String rawAnswer, java.util.function.Consumer<String> onToken) {
        return streamExplain(stem, pointsJson, byConceptJson, rawAnswer, onToken, null);
    }

    public String streamExplain(String stem, String pointsJson, String byConceptJson,
                                String rawAnswer, java.util.function.Consumer<String> onToken,
                                java.util.function.Consumer<String> onReasoning) {
        String pointsText = formatPoints(pointsJson);
        String verdictsText = formatVerdicts(byConceptJson);
        String user = String.format("""
                题目：
                %s

                评分点：
                %s

                判分要点：
                %s

                学生的答案：
                %s

                请讲解这道题。
                """, stem, pointsText, verdictsText, rawAnswer == null ? "（未作答）" : rawAnswer);

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
     *   <li>引导思考而非直接讲解答案</li>
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
        return streamChat(stem, pointsJson, turns, onToken, null, false);
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
        String pointsText = formatPoints(pointsJson);

        // 对话历史只保留最近几轮，且每条文本截断到合理长度：
        // 聊天不限轮数，若把全部历史拼进 prompt，轮数多了会撑爆模型上下文（LLM 返回 400/失败）。
        StringBuilder history = new StringBuilder();
        int from = Math.max(0, turns.size() - 8);   // 最多带最近 8 条
        for (int i = from; i < turns.size(); i++) {
            DrillTurn t = turns.get(i);
            String answer = t.getRawAnswer();
            if (answer != null && !answer.isBlank()) {
                history.append("学生: ").append(truncate(answer, 500)).append("\n\n");
            }
            String tutor = t.getTutorText();
            if (tutor != null && !tutor.isBlank()) {
                history.append("老师: ").append(truncate(tutor, 500)).append("\n\n");
            }
        }

        String user = String.format("""
                题目：
                %s

                评分要点（供你参考，不要直接告诉学生）：
                %s

                对话历史：
                %s

                请回复学生的最新消息。
                """, stem, pointsText, history.toString().isBlank() ? "（无）" : history.toString());

        StringBuilder buf = new StringBuilder();
        String system = reveal ? CHAT_REVEAL_SYSTEM_PROMPT : CHAT_SYSTEM_PROMPT;
        rawClient.stream(system, user,
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