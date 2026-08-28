package interview.homegrown.modules.drill.ai;

import interview.homegrown.common.ai.StructuredOutputInvoker;
import org.springframework.stereotype.Component;

/**
 * 苏格拉底判分器：用户每轮作答后调用，AI 结构化判定三态。
 *
 * <p>判据（已与用户确认）：
 * <ul>
 *   <li><b>不算答完</b>（answering）：澄清题意、复述要求、没进入实质作答 → 不评分不引导，AI 简短确认并等</li>
 *   <li><b>算答完</b>（needs_guide/done）：用户开始实质作答且自然停、或显式说"答完了/不懂了"</li>
 *   <li><b>达标</b>（done）：评分点覆盖≥80% 且无致命缺漏 → 表扬+提示结束；G1 未达标则触发再考查</li>
 *   <li><b>未达标</b>（needs_guide）：答完但覆盖<80% 或有致命缺漏 → AI 抛一个引导问题（不给答案）</li>
 * </ul>
 *
 * <p>引导策略：逐步追问不给答案，除非用户明确"不会了"才给答案+原因（此时 revealed=true，最终分封 AGAIN）。
 */
@Component
public class SocraticJudgeService {

    private final StructuredOutputInvoker invoker;

    public SocraticJudgeService(StructuredOutputInvoker invoker) {
        this.invoker = invoker;
    }

    private static final String SYSTEM = """
            你是一位苏格拉底式导师。用户刚在一道练习题上提交了一轮作答，你要判定他是否答完、是否达标，
            并决定下一步动作。严格按以下判据：

            【答完判定】
            - 不算答完（state=answering）：用户在澄清题意（"这题是问 XXX 吗？""没懂第二点"）、
              复述要求、还没进入实质作答。此时 coverage=0、fatalGap=false、guideQuestion 和 praise 留空。
            - 算答完（state=needs_guide 或 done）：用户对评分点有实质作答（哪怕答错、答不全），
              且表达完整意图后停手，或显式说"答完了/就这样/不懂了"。

            【达标判定】（仅答完时算）
            - 达标（state=done）：评分点覆盖≥80% 且无致命缺漏。praise 给一句简短表扬并提示用户可结束本题。
              coverage 填实际覆盖度（0~1），fatalGap=false。
            - 未达标（state=needs_guide）：覆盖<80% 或有致命缺漏。guideQuestion 给一个引导性问题
              （只问、不给答案、不替学生总结），coverage 填实际值，fatalGap 按实填。

            【用户意图判定：wantsAnswerNow】
            根据用户的真实意图判断他是否在<b>明确索要完整答案 / 放弃独立作答</b>，而不是按关键字猜：
            - wantsAnswerNow=true 的情形（用户明示放弃自己作答、要 AI 直接给）：
              直接说"给我答案/直接告诉我/答案是什么/我不会做/不会了/做不出来/放弃/看答案"、
              "give me the answer / I don't know / I give up" 等<b>明确表达</b>；
              或先尝试过、已明确表示"实在做不出来了/不想做了，给我吧"。
            - wantsAnswerNow=false 的情形（用户仍在学习/作答，只是提问或流露一点不自信）：
              "这道题怎么做"、"如何实现"、"是不是用 X"、"我不确定"、"我这样写对吗"、
              "没思路，能提示一下吗"（要提示≠要答案）、尝试作答后问"对不对"、
              夹杂大量作答内容或代码的提问。
            判定依据是用户整体表达的<b>意图</b>：是否明确放弃独立作答、把最后一手交出去。
            犹豫、求提示、确认自己想法的都不是 wantsAnswerNow。

            【硬约束】
            - guideQuestion 绝不能直接给答案或替学生总结；只能用提问引导他自己想出来（wantsAnswerNow=false 时）。
            - praise 绝不能在未达标时出现。
            - wantsAnswerNow=true 时，guideQuestion/praise 留空，state 可填 answering（该轮不再引导）。
            - 输出严格遵循格式说明的 JSON。
            """;

    private static final String PASS_THRESHOLD = "0.8";

    /**
     * 判定用户当前轮作答。
     *
     * @param stem         题干
     * @param pointsJson   评分点 JSON
     * @param userAnswer   用户本轮作答
     * @param conversation 之前的对话实录（老师问/学生答），供判定参考；可为空
     * @return 三态判定结果
     */
    public SocraticJudge judge(String stem, String pointsJson, String userAnswer, String conversation) {
        String user = """
                题干：
                %s

                评分点（JSON）：
                %s

                用户本轮作答：
                %s

                之前的对话实录（供参考，判断哪些点已被实际考到）：
                %s

                达标阈值：评分点覆盖 ≥ %s 且无致命缺漏。
                请判定 state / coverage / fatalGap / guideQuestion / praise / wantsAnswerNow。
                """.formatted(
                stem == null ? "" : stem,
                pointsJson == null ? "" : pointsJson,
                userAnswer == null ? "" : userAnswer,
                conversation == null ? "（无）" : conversation,
                PASS_THRESHOLD);

        return invoker.invoke(SYSTEM, user, SocraticJudge.class);
    }
}
