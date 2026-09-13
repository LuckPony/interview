package interview.homegrown.modules.interview.service;

import interview.homegrown.common.ai.StructuredOutputInvoker;
import interview.homegrown.modules.interview.model.InterviewDifficulty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 动态追问决策：模型先判断回答是否真的值得深挖，再在需要时生成一个针对性追问。
 * 清晰完整的回答直接进入下一道主问题；模糊、矛盾、缺少实现依据或暴露关键技术点时才追问。
 */
@Service
public class FollowupGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(FollowupGeneratorService.class);

    private final StructuredOutputInvoker invoker;

    public FollowupGeneratorService(StructuredOutputInvoker invoker) {
        this.invoker = invoker;
    }

    private static final String SYSTEM_PROMPT = """
            你是一位重视覆盖面与信息增益的资深技术面试官。
            你要先判断候选人的当前回答是否值得追问，而不是机械地为每题追问。
            只输出 JSON：
            {"shouldFollowUp": true或false, "question": "需要追问时的问题，否则为空字符串", "reason": "一句话决策依据"}
            """;

    /**
     * 判断是否需要追问，并在需要时生成下一问。completedFollowUps 表示本主问题已经追问过几次。
     */
    public FollowUpDecision decideFollowUp(InterviewDifficulty difficulty,
                                           String skillName,
                                           String baseQuestion,
                                           String currentQuestion,
                                           String userAnswer,
                                           int completedFollowUps,
                                           int maxFollowUps,
                                           String provider) {
        if (completedFollowUps >= maxFollowUps) {
            return FollowUpDecision.next("已达到最大追问次数");
        }

        String roundHint = switch (difficulty) {
            case JUNIOR -> "一面：只深挖项目真实性、个人职责、具体实现步骤和为什么这样做，不突然转成脱离项目的难题。";
            case MIDDLE -> "二面：优先验证回答中涉及的机制、原理、边界条件、数据一致性和方案权衡。";
            case SENIOR -> "三面：优先验证架构约束、容量与性能、高可用、生产排障、长期成本和演进取舍。";
        };

        String userPrompt = """
                面试方向：__SKILL__
                当前轮次要求：__ROUND__

                主问题：__BASE__
                刚刚实际提问：__CURRENT__

                候选人的回答：__ANSWER__

                当前主问题已经追问 __DONE__ 次，最多允许 __MAX__ 次。

                决策规则：
                1. 回答已经具体、逻辑闭环且足以判断能力时，shouldFollowUp=false，尽快进入下一道主问题以保证覆盖面。
                2. 回答明确表示不会、不清楚或没有相关经历时，shouldFollowUp=false，不浪费追问次数。
                3. 只有回答模糊、遗漏关键步骤、存在矛盾、像背诵结论而没有依据，或出现一个高价值技术声明需要核实时，才 shouldFollowUp=true。
                4. 追问必须紧扣候选人刚才说过的内容，只问一个最有信息增益的问题；不得重复原题，不得换一个无关知识点。
                5. 已经追问过一次后，第二次追问门槛更高：只有仍存在影响能力判断的关键缺口才继续。
                """
                .replace("__SKILL__", skillName == null ? "" : skillName)
                .replace("__ROUND__", roundHint)
                .replace("__BASE__", baseQuestion)
                .replace("__CURRENT__", currentQuestion)
                .replace("__ANSWER__", userAnswer)
                .replace("__DONE__", String.valueOf(completedFollowUps))
                .replace("__MAX__", String.valueOf(maxFollowUps));

        FollowUpDecision decision = invoker.invoke(
                SYSTEM_PROMPT, userPrompt, FollowUpDecision.class, provider);
        if (!decision.shouldFollowUp() || decision.question() == null || decision.question().isBlank()) {
            String reason = decision.reason() == null ? "回答无需继续追问" : decision.reason();
            log.debug("跳过追问: completed={}, reason={}", completedFollowUps, reason);
            return FollowUpDecision.next(reason);
        }
        log.debug("决定追问: nextIndex={}, len={}, reason={}",
                completedFollowUps + 1, decision.question().length(), decision.reason());
        return decision;
    }

    public record FollowUpDecision(boolean shouldFollowUp, String question, String reason) {
        public static FollowUpDecision next(String reason) {
            return new FollowUpDecision(false, "", reason);
        }
    }
}
