package interview.homegrown.modules.interview.service;

import interview.homegrown.common.ai.StructuredOutputInvoker;
import interview.homegrown.modules.interview.model.DifficultyConfig;
import interview.homegrown.modules.interview.model.InterviewDifficulty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 动态追问生成：候选人在回答主问题后，根据其<b>实际回答内容</b>生成下一个追问。
 * <p>自适应策略：若候选人表示"不清楚/不会"，第一个追问改为最基础的概念确认问题；
 * 若基础问题仍答不上来，则由上层（InterviewSessionService）停止追问、直接进入下一道基础题。</p>
 */
@Service
public class FollowupGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(FollowupGeneratorService.class);

    private final StructuredOutputInvoker invoker;

    public FollowupGeneratorService(StructuredOutputInvoker invoker) {
        this.invoker = invoker;
    }

    private static final String SYSTEM_PROMPT = """
            你是一位资深的技术面试官，正在对候选人进行追问。
            根据候选人对问题的实际回答，生成一个恰当的追问，引导候选人深入思考或补充细节。
            只输出 JSON：{"question": "追问内容"}，不要输出其他内容。
            """;

    /**
     * 生成下一个追问
     *
     * @param basicCheck 候选人表示"不清楚/不会"时 true：生成最基础的概念确认问题，
     *                   用于确认候选人是否真的完全不会（若基础也答不上来，上层将停止追问）
     */
    public String generateFollowUp(InterviewDifficulty difficulty,
                                   String skillName,
                                   String baseQuestion,
                                   String userAnswer,
                                   int followUpIndex,
                                   int totalFollowUps,
                                   String provider,
                                   boolean basicCheck) {

        String depthHint;
        if (basicCheck) {
            depthHint = "候选人表示对这个知识点不太清楚。请用最基础、最直白的概念问题来确认" +
                    "（例如直接问「什么是什么/怎么用一句话解释」），考察 TA 是否完全不会；问题要非常简单，不要有任何深度。这是第 "
                    + (followUpIndex + 1) + "/" + totalFollowUps + " 个追问。";
        } else {
            depthHint = switch (difficulty) {
                case JUNIOR -> "追问要浅显、贴近基础，考察是否理解基本概念，避免过难；这是第 " + (followUpIndex + 1) + "/" + totalFollowUps + " 个追问";
                case MIDDLE -> "追问要有一定深度，引导候选人解释原理、对比方案或说明权衡；这是第 " + (followUpIndex + 1) + "/" + totalFollowUps + " 个追问";
                case SENIOR -> "追问要非常深入，考察候选人是否真的掌握而非空口说——让 TA 讲清机制、边界条件、生产环境中的取舍与排错思路；这是第 " + (followUpIndex + 1) + "/" + totalFollowUps + " 个追问";
            };
        }

        // 用占位替换拼接，避免用户回答中的 % 等格式符触发异常
        String userPrompt = """
                面试方向：__SKILL__

                主问题：__BASE__

                候选人的回答：__ANSWER__

                追问要求：__DEPTH__
                请生成一个自然的追问，最好能针对回答中的漏洞、可深挖的点或未说清楚的地方。
                """
                .replace("__SKILL__", skillName == null ? "" : skillName)
                .replace("__BASE__", baseQuestion)
                .replace("__ANSWER__", userAnswer)
                .replace("__DEPTH__", depthHint);

        FollowUpDraft draft = invoker.invoke(SYSTEM_PROMPT, userPrompt, FollowUpDraft.class, provider);
        if (draft.question() == null || draft.question().isBlank()) {
            throw new IllegalStateException("追问生成为空");
        }
        log.debug("追问生成: index={}, basicCheck={}, len={}", followUpIndex, basicCheck, draft.question().length());
        return draft.question();
    }

    /** 兼容旧调用（默认非基础确认） */
    public String generateFollowUp(InterviewDifficulty difficulty,
                                   String skillName,
                                   String baseQuestion,
                                   String userAnswer,
                                   int followUpIndex,
                                   int totalFollowUps,
                                   String provider) {
        return generateFollowUp(difficulty, skillName, baseQuestion, userAnswer,
                followUpIndex, totalFollowUps, provider, false);
    }

    public record FollowUpDraft(String question) {
    }
}
