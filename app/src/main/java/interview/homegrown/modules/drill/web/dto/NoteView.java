package interview.homegrown.modules.drill.web.dto;

/**
 * 笔记提交回执。overlapRatio 回传是刻意的：让用户看见"你这段有 28% 是从题干搬来的"，
 * 比单纯拒收更有教育意义。
 *
 * @param overlapRatio 与题干/评分点的 trigram 包含度（0-1）
 * @param debtLeft     写完这条后还欠多少条内化笔记
 */
public record NoteView(
        Long runId,
        Long noteId,
        double overlapRatio,
        int debtLeft
) {
}
