package interview.homegrown.modules.drill.web.dto;

import java.time.Instant;

/**
 * 问答记录列表项：按题（questionId）聚合后的摘要。
 * runId/rawScore/grade/answeredAt 取该题<b>最近一次</b>判分；runCount = 该题练过几轮（含重答与追问场）。
 * status = 最近一次 run 的状态（GRADED / ANSWERING / READY），前端据此区分「重练此题」与「继续对话」。
 */
public record RunSummaryView(
        Long runId,
        String stem,
        double rawScore,
        String grade,
        Instant answeredAt,
        boolean hasNote,
        Long questionId,
        int runCount,
        String status,
        Long planId
) {
}
