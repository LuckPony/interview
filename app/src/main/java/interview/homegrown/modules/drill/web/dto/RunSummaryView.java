package interview.homegrown.modules.drill.web.dto;

import java.time.Instant;

/**
 * 问答记录列表项：一次已判分作答的摘要。
 */
public record RunSummaryView(
        Long runId,
        String stem,
        double rawScore,
        String grade,
        Instant answeredAt,
        boolean hasNote
) {
}
