package interview.homegrown.modules.drill.ai;

import interview.homegrown.common.ai.StructuredOutputInvoker;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * REHEARSAL 追问生成器（痛点 4）。
 *
 * <p>真实面试的杀伤力不在第一问，而在"你说完之后他继续问"。这里让 LLM 基于用户上一轮的
 * <b>具体表述</b>生成深挖或反驳，逼出"扛追问"能力。
 *
 * <p>决策权仍在服务端：<b>问几轮由服务端封顶（最多 2 轮）</b>，LLM 只负责这一轮问什么。
 * 它无权决定继续追问还是终止 —— 否则又变成 LLM 自编排。
 */
@Component
public class FollowupGenerator {

    private final StructuredOutputInvoker invoker;

    public FollowupGenerator(StructuredOutputInvoker invoker) {
        this.invoker = invoker;
    }

    /**
     * @param round 第几轮追问（1 或 2）：第 1 轮深挖机制，第 2 轮反驳/极端场景
     */
    public GeneratedQuestion generate(String previousStem, String previousAnswer,
                                      List<String> conceptNames, int round) {
        String angle = round <= 1
                ? "顺着用户的回答往下深挖一层机制或实现细节，问他刚才那句话背后的原因"
                : "对用户的回答提出一个反驳、边界条件或极端故障场景，看他的结论是否还站得住";

        String system = """
                你是一位资深技术面试官，正在做连续追问。
                只产出下一个追问的题干与评分点，严禁给出答案、解析或点评上一轮表现。
                追问必须基于用户上一轮的具体表述，不能是可以脱离上下文单独提出的通用问题，
                也不能只是把上一问换个说法重复。
                直接提问，不要复述、解释、点评用户上一轮，也不要以"你说…""你的意思是…"等元语言开头。
                byConcept 只需一组，conceptIndex 填 0。
                不要使用中文破折号，输出严格遵循格式说明的 JSON。""";

        String user = String.format("""
                本轮涉及概念：%s
                上一个问题：%s
                用户的回答：%s

                请生成第 %d 轮追问。追问角度：%s。
                评分点 2-4 条，必须是这轮追问本身能被客观核验的要点。
                """, String.join("、", conceptNames), previousStem, previousAnswer, round, angle);

        return invoker.invoke(system, user, GeneratedQuestion.class);
    }
}
