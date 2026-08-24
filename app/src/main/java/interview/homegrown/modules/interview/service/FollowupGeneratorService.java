package interview.homegrown.modules.interview.service;

import interview.homegrown.common.ai.StructuredOutputInvoker;
import interview.homegrown.modules.interview.model.DifficultyConfig;
import interview.homegrown.modules.interview.model.InterviewDifficulty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 动态追问生成：候选人在回答主问题后，根据其<b>实际回答内容</b>生成下一个追问。
 * 追问的深度与数量由难度决定（初级浅、中级中、高级深），数量由 {@link DifficultyConfig#followUpCount()} 控制。
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
            根据候选人对主问题的实际回答，生成一个恰当的追问，引导候选人深入思考或补充细节。
            只输出 JSON：{"question": "追问内容"}，不要输出其他内容。
            """;

    /** 生成下一个追问（单条） */
    public String generateFollowUp(InterviewDifficulty difficulty,
                                   String skillName,
                                   String baseQuestion,
                                   String userAnswer,
                                   int followUpIndex,
                                   int totalFollowUps,
                                   String provider) {

        String depthHint = switch (difficulty) {
            case JUNIOR -> "追问要浅显、贴近基础，考察是否理解基本概念，避免过难；这是第 " + (followUpIndex + 1) + "/" + totalFollowUps + " 个追问";
            case MIDDLE -> "追问要有一定深度，引导候选人解释原理、对比方案或说明权衡；这是第 " + (followUpIndex + 1) + "/" + totalFollowUps + " 个追问";
            case SENIOR -> "追问要非常深入，考察候选人是否真的掌握而非空口说——让 TA 讲清机制、边界条件、生产环境中的取舍与排错思路；这是第 " + (followUpIndex + 1) + "/" + totalFollowUps + " 个追问";
        };

        // 注意：不能用 String.formatted 拼用户回答（可能含 % 触发格式异常），用占位替换拼接
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
        log.debug("追问生成: index={}, question={}", followUpIndex, draft.question().length());
        return draft.question();
    }

    public record FollowUpDraft(String question) {
    }
}
