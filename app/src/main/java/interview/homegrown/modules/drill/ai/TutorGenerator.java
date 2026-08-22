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
               只要涉及程序行为、API、配置、数据结构或实现方式，就必须给出最小代码/配置，
               并用围栏代码块（以 ```语言 开头、``` 结尾，原样保留缩进与换行）；
               涉及调用链、生命周期、架构、状态流转或图片式关系时，给出 ```mermaid 图；
               严禁只抽象描述一段并未展示的代码
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
    public static final String FINISH_MARKER = "<!--DRILL_FINISH-->";

    private static final String CHAT_SYSTEM_PROMPT = """
            你是一位耐心的技术辅导老师，正在一对一辅导学生做一道技术题。
            你的目标是帮学生<b>自己</b>把这道题想明白，而不是替他想。
            1. 学生回答正确且完整：禁止再问“理解了吗”“懂了吗”等确认性问题。先判断候选追问是否还能带来新的、
               与当前层级匹配的考察价值；有价值就直接追问恰好一个，没有就明确说明本题已完成，
               并在回复末尾原样附加不可见标记 <!--DRILL_FINISH-->，由系统自动结束评分，
               不要为了延长对话强行追问
            2. 学生回答错误或不完整：先指出哪里不对、缺了什么，并用简单例子或最小代码把当前问题解释清楚，
               然后直接请他修正当前答案；不要问“是否理解”，不得立刻切换到新的知识点或下一道追问
            3. 学生提出疑问、请求解释或索要某段代码：必须优先完整回答他当前问的内容，不要回避、不要反问他想看哪一种；
               回答后若需要验证掌握，直接让他修正答案或完成一个具体追问，禁止询问“是否理解”
            4. 学生只是确认自己的答案对不对：明确告诉他是正确、部分正确还是不正确，并把原因解释清楚；
               完全正确时按规则 1 直接追问或收尾，非完全正确时直接请他修正，不问是否理解
            5. 学生明确索要答案或提示（如“告诉我答案”“给我完整代码”“答案是什么”）：告诉他可以点输入框旁的
               「看答案」按钮获取完整讲解，不要在这里直接给出答案
            6. 回复 100-240 字中文，优先使用通俗语言，从最小例子讲起；必须使用专业词时紧接一句白话解释
            7. 不要使用中文破折号（——/-），改用逗号或句号
            8. 不要提及评分、分数、判分、得分等概念
            9. 可以用 Markdown 排版（加粗关键点、必要时用列表）；
               涉及程序行为时优先给一段不泄露完整答案的最小代码/伪代码或具体输入输出，
               并用围栏代码块（```语言 ... ```，原样保留缩进与换行）；
               涉及链路、生命周期或状态流转时可给 ```mermaid 图，不要只做抽象描述
            9. 结尾必须是一句完整的话
            """;

    /**
     * 追问模式：判分前对话的默认行为（取代旧 CHAT_SYSTEM_PROMPT 的泛化引导）。
     *
     * <p>主问之后，每次学生作答，AI 都先<b>判断理解</b>，再<b>视情况</b>决定下一步：
     * 懂了且有下一小问 → 问一条；没懂 → 先引导修正；全懂了/清单问完 → 停止追问并提示结束。
     * 小问总数由「待追问清单」（出题时生成的 followups，2-4 条）封顶，AI 无权自行新增，
     * 也不一定非要把清单问完——学生提前掌握就提前收。
     */
    private static final String CHAT_FOLLOWUP_SYSTEM_PROMPT = """
            你是一位耐心的技术辅导老师，正在一对一辅导学生做一道技术题（主问之后逐条追问）。
            先判断学生最新消息属于哪种情况，并严格只执行对应动作：
            1. 学生提出疑问、表示不懂、要求解释或要求代码：
               必须先直接、完整地解决当前疑问。用通俗语言和最小例子说明，不得回避，不得反问他想看哪一种，
               不得提出无关的新问题。解释后直接请他修正当前答案或回答一个具体的候选追问，禁止问“理解了吗”。
            2. 学生回答错误或不完整：
               先明确纠正错误，把缺失部分解释到足以继续作答；本轮不进入新的知识点，直接请他修正当前答案，
               禁止问“是否理解”“现在懂了吗”。
            3. 上一轮老师正在讲解，而学生继续提出疑问：
               继续解答当前疑问；解答后直接要求一次可作答、可验证的回应，不做空泛的理解确认。
            4. 学生明确确认已经理解：
               可以从“待追问的小问”中选择一个与当前学习层级匹配且真正有价值的问题，但不是必须追问；
               若当前知识已足够或继续深挖会明显超出层级，直接肯定并在回复末尾原样附加
               <!--DRILL_FINISH-->，由系统自动结束评分。
            5. 学生第一次就把当前问题回答得完全正确、理由完整且没有疑问：
               先检查待追问清单。有值得继续考察的内容就直接提出恰好一个小问；没有就立即收尾并提示
               “本题已完成”，并在回复末尾原样附加 <!--DRILL_FINISH-->，由系统自动结束评分。
               禁止询问他是否理解，也禁止为了凑轮数强行追问。
            6. 提出追问时只能问【恰好一个】小问，并且必须来自待追问清单；禁止重复题目主问，
               禁止重新粘贴原题，禁止自行创造更深、更偏的新问题，禁止一次问多个并列问题。
            7. 待追问清单为空、已经问过、与学生当前层级不匹配，或学生已经表现出充分掌握时，必须停止追问，
               并在回复末尾原样附加 <!--DRILL_FINISH-->。
            8. 除学生明确索要答案外，避免直接给出整道主问题的标准答案；但学生提出的具体疑问必须讲清楚，
               不能用“先说说你的想法”来搪塞。
            9. 在任何情况下都禁止询问“理解了吗”“懂了吗”“是否清楚”等确认性问题。理解程度必须通过用户的
               实际回答判断：正确就直接追问或收尾，不正确就解释后让他修正。
            10. 回复 100-240 字中文，优先白话和最小示例；不要使用中文破折号，不要提及评分、分数、判分、得分。
            11. 可以用 Markdown 排版；涉及代码时用围栏代码块。结尾必须是一句完整的话。
            """;

    /**
     * 追问收尾模式：追问已到服务端封顶（{@link #MAX_FOLLOWUPS} 个）。
     * AI 不再提新问题，而是做整体点评并引导学生去「结束并评分」，答案同样留到揭示后再给。
     */
    private static final String CHAT_WRAPUP_SYSTEM_PROMPT = """
            你是一位耐心的技术辅导老师，正在一对一辅导学生做一道技术题。
            这道题的小问已经全部问完（服务端封顶），现在请你：
            1. 用 100-200 字中文做整体点评：把学生这轮回答里最值得肯定的点和最需要补的地方
               各说一点，帮他对全局有个印象；
            2. 明确说明本题对话已经结束，并在回复末尾原样附加 <!--DRILL_FINISH-->，由系统自动评分；
            3. 严禁再提出新的问题，也严禁直接给出完整正确答案、标准实现或完整解题步骤。
            4. 不要使用中文破折号（——/-），不要提及分数、判分、得分等概念；
               可以用 Markdown 排版，结尾必须是一句完整的话。
            """;

    /**
     * 揭示答案模式：学生已明确索要答案/提示（点「看答案」按钮或自然语言被服务端识别），
     * 直接给完整讲解；此时<b>不再有后续小问</b>。与 {@link #CHAT_FOLLOWUP_SYSTEM_PROMPT} 互补，由服务端显式选择。
     *
     * <p>按进度揭示（用户决策）：只看「当前小问」的答案，不一次倒出全部。
     * 当前小问 = 对话历史里最近一个由老师提出、学生尚未完整答完的问题。
     */
    private static final String CHAT_REVEAL_SYSTEM_PROMPT = """
            你是一位耐心的技术辅导老师，正在一对一辅导学生做一道技术题。
            学生已经明确表示想看答案。请先根据对话历史判断<b>当前正在讨论的小问</b>：
            即对话中最近一个由你（老师）提出、学生尚未完整答完的问题。然后：
            1. 完整回答学生当前明确索要的内容。如果他要完整代码，就直接给可运行的完整代码，
               不要再反问“想看代码还是思路”，也不要只给提示；
            2. 只讲透当前正在讨论的问题，使用从简单到复杂的顺序，专业术语后补一句白话解释；
            3. 学生此前已确认理解过的更早小问，一句话带过即可，不要重复展开；
            4. 不要一次把无关的所有小问答案全部倒出；
            5. 如学生之前有作答，简要指出哪里对、哪里需要修正；
            6. 给出完整解答后，不得提出新的知识性追问，也不得询问“是否理解”。明确说明参考答案已经给出、
               本题对话到此结束，并在回复末尾原样附加 <!--DRILL_FINISH-->，由系统按答案揭示前的独立回答自动评分。
            7. 回复 150-500 字中文，代码本身不计入字数限制，像聊天一样自然
            8. 不要使用中文破折号（——/-），改用逗号或句号
            9. 不要提及评分、分数、判分、得分等概念
            10. 可以用 Markdown 排版（加粗关键点、必要时用列表）；
               涉及代码时用围栏代码块（```语言 ... ```，代码原样保留缩进与换行），不要写成普通段落
            11. 结尾必须是一句完整的话
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

    /** 判分前主问之后的小问封顶（提示词约束 AI 最多问 4 个小问，问满即收尾） */
    public static final int MAX_FOLLOWUPS = 4;
    /** 判分前对话安全阀：学生回答超过此轮数强制收尾（防 AI 不听话无限追问，正常流程到不了） */
    public static final int SAFETY_ANSWER_CAP = 12;

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
        return streamChat(stem, pointsJson, turns, onToken, null, false, 1, SAFETY_ANSWER_CAP);
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
     * @param followupIndex 判分前学生已作答的轮数（含当前这条），仅作<b>安全阀</b>：
     *                      超过 {@code maxAnswers} 强制收尾（正常流程由 AI 自行按 4 个小问封顶）；
     *                      判分后自由问答传 -1
     * @param maxAnswers    判分前回答轮数安全阀（超过则不再追问，走收尾）
     * @return 完整回复文本（失败/空为 null）
     */
    public String streamChat(String stem, String pointsJson, List<DrillTurn> turns,
                             java.util.function.Consumer<String> onToken,
                             java.util.function.Consumer<String> onReasoning,
                             boolean reveal, int followupIndex, int maxAnswers) {
        return streamChat(stem, pointsJson, null, turns, null, onToken, onReasoning,
                reveal, followupIndex, maxAnswers);
    }

    /**
     * 带学习上下文（学生进度/概念要点/资料块/互联网补充）的完整版。
     * 上下文仅作参考素材：追问可结合资料细节，也可用通用知识；引用资料内容时注明出处（C3）。
     */
    public String streamChat(String stem, String pointsJson, List<String> followups,
                             List<DrillTurn> turns, String context,
                             java.util.function.Consumer<String> onToken,
                             java.util.function.Consumer<String> onReasoning,
                             boolean reveal, int followupIndex, int maxAnswers) {
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

        String contextBlock = (context == null || context.isBlank())
                ? ""
                : "\n\n学习上下文（学生进度 / 概念要点 / 用户资料 / 互联网补充，作为参考素材，引用时可注明出处）：\n" + context;
        String user = String.format("""
                题目：
                %s

                评分要点（供你参考，不要直接告诉学生）：
                %s

                待追问的小问（按顺序逐条问，问完就停、不得自己新增；学生提前掌握可提前收尾）：
                %s

                对话历史：
                %s
                %s

                请回复学生的最新消息。
                """, stem, pointsText, formatFollowups(followups),
                history.toString().isBlank() ? "（无）" : history.toString(), contextBlock);

        StringBuilder buf = new StringBuilder();
        // 模式选择：reveal 优先（答案已揭示 → 完整讲解，不再追问）；
        // 判分后自由问答（followupIndex=-1）走通用苏格拉底引导；
        // 判分前：未超安全阀 → 追问模式（AI 自行判断是否出下一小问）；已超 → 收尾提示结束。
        String system;
        if (reveal) {
            system = CHAT_REVEAL_SYSTEM_PROMPT;
        } else if (followupIndex < 0) {
            system = CHAT_SYSTEM_PROMPT;
        } else if (followupIndex <= maxAnswers) {
            system = CHAT_FOLLOWUP_SYSTEM_PROMPT;
        } else {
            system = CHAT_WRAPUP_SYSTEM_PROMPT;
        }
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

    /** 把待追问的小问清单渲染成编号列表（空则显示「无」） */
    private static String formatFollowups(List<String> followups) {
        if (followups == null || followups.isEmpty()) return "（无）";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < followups.size(); i++) {
            sb.append(i + 1).append(". ").append(followups.get(i)).append('\n');
        }
        return sb.toString().trim();
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