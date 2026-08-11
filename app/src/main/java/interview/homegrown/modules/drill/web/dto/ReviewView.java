package interview.homegrown.modules.drill.web.dto;

import java.util.List;

/**
 * AI 复盘报告：题目 + 分数 + 薄弱点清单 + 对话总结（欠缺）+ 解题思路 + 记忆口诀。
 * 复盘页一次调用即可渲染全部内容。
 */
public record ReviewView(
        Long runId,
        String stem,
        double rawScore,
        List<String> weakPoints,
        String gapSummary,
        String approach,
        String mnemonic
) {
}
