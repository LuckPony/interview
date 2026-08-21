package interview.homegrown.modules.drill.web.dto;

import java.util.List;

/**
 * AI 复盘报告：题目 + 分数 + 薄弱点清单 + 对话总结（欠缺）+ 解题思路 + 记忆口诀，
 * 以及已保存的自省笔记（无笔记时 myWords/gapFound/nextAction 均为 null，前端据此切换写/读态）。
 */
public record ReviewView(
        Long runId,
        String stem,
        double rawScore,
        List<String> weakPoints,
        String gapSummary,
        String approach,
        String mnemonic,
        String myWords,
        String gapFound,
        String nextAction
) {
}
