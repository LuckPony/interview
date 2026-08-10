package interview.homegrown.modules.drill.web.dto;

import java.util.List;

/**
 * 模拟面试的统一响应。finished=false 时是"下一问"，true 时是本场结算。
 *
 * <p>刻意不返回标准答案或讲解（痛点 7）：只回逐点判定与暴露的缺口，
 * 用户要靠自己复述才能内化，见 POST /api/drill/{runId}/note。
 */
public record RehearsalView(
        Long runId,
        int round,
        int maxRound,
        String stem,
        boolean finished,
        Double score,
        String grade,
        Boolean allPassed,
        List<String> roundScores,
        String byConceptJson
) {

    public static RehearsalView asking(Long runId, int round, int maxRound, String stem) {
        return new RehearsalView(runId, round, maxRound, stem, false, null, null, null, List.of(), null);
    }
}
