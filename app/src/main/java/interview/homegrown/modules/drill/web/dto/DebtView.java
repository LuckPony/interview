package interview.homegrown.modules.drill.web.dto;

import java.time.Instant;

/**
 * 一条内化欠账：答错了、还没复盘的题。
 */
public record DebtView(
        Long runId,
        String stem,
        double rawScore,
        Instant answeredAt
) {
}
