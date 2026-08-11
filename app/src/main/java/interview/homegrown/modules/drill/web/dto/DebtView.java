package interview.homegrown.modules.drill.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 一条内化欠账：答错了（低于达标线）、还没复盘的题。
 *
 * <p>weakPoints 是判分结果里没打中的评分点（MISS/PARTIAL），供复盘页直接展示"哪里薄弱"。
 * conceptId / planId 供「判断自测」（对该概念出新题）与「按方向分组」使用。
 */
public record DebtView(
        Long runId,
        String stem,
        double rawScore,
        Instant answeredAt,
        List<String> weakPoints,
        Long conceptId,
        Long planId
) {
}
