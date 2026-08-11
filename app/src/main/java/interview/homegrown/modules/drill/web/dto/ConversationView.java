package interview.homegrown.modules.drill.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 一道题的完整对话线：该 questionId 下所有 run（LEARN 原答 + restart 重答 + REHEARSAL 追问场）
 * 按时间顺序串成一条线。前端点开问答记录卡片后渲染成"对话历史"，可回看每轮问答与判分。
 */
public record ConversationView(
        Long questionId,
        String stem,
        String probeType,
        String responseFormat,
        List<ConversationRunView> runs
) {
    public record ConversationRunView(
            Long runId,
            String mode,               // LEARN / REHEARSAL
            String status,             // GRADED（仅展示已判分的）
            Long sourceRunId,          // REHEARSAL 追问场指向其来源 LEARN run；LEARN 为 null
            double rawScore,
            String grade,
            Instant answeredAt,
            List<ConversationTurnView> turns
    ) {
    }

    public record ConversationTurnView(
            int round,
            String stem,
            String rawAnswer,
            double rawScore,
            Boolean passed,
            String byConceptJson,
            String tutorText
    ) {
    }
}
