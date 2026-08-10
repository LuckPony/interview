package interview.homegrown.modules.drill.web.dto;

/** 出题响应：题目内容 + 作答实例 id（提交时回传）。 */
public record QuestionView(Long runId, String stem, String probeType, String responseFormat) {
}
