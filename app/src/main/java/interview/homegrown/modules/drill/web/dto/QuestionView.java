package interview.homegrown.modules.drill.web.dto;

/**
 * 出题响应：题目内容 + 作答实例 id（提交时回传）。
 * questionId 暴露供前端拉取该题的完整对话线（History "继续练习" 进入答题页面时，对话气泡可继续显示历史问答）。
 */
public record QuestionView(
        Long runId,
        Long questionId,
        String stem,
        String probeType,
        String responseFormat
) {
}
