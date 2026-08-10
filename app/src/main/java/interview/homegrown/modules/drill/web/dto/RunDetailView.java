package interview.homegrown.modules.drill.web.dto;

import java.time.Instant;

/**
 * 问答记录详情：题目 + 用户答案 + 判分明细 + 是否有笔记 + 关联概念（供"继续追问"接力 REHEARSAL）。
 */
public record RunDetailView(
        Long runId,
        Long questionId,
        String stem,
        String probeType,
        String responseFormat,
        String rawAnswer,
        double rawScore,
        String grade,
        String byConceptJson,
        Instant answeredAt,
        boolean hasNote,
        Long[] conceptIds
) {
}
