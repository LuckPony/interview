package interview.homegrown.modules.interview.model;


import java.util.List;

//LLM 面试评估的结构化输出
public record InterviewEvaluationResult(
        int totalScore,
        //逐题评价（按题目顺序）
        List<QuestionEvaluation> questionEvaluations,
        //优势
        List<String> strength,
        //改进建议
        List<String> improvements
) {
    public record QuestionEvaluation(
            int score,
            String feedback,
            List<ScoringPoint> scoringPoints
    ) {}

    /**
     * 一项可核验的评分依据。maxScore 是该项满分，awardedScore 是实际得分；
     * status 由服务端根据两者统一为 ACHIEVED / PARTIAL / MISSED。
     */
    public record ScoringPoint(
            String criterion,
            int maxScore,
            int awardedScore,
            String status,
            String evidence,
            String reason
    ) {}
}
