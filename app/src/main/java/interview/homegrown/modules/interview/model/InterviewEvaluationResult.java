package interview.homegrown.modules.interview.model;


import java.util.List;

//LLM 面试评估的结构化输出
public record InterviewEvaluationResult(
        int totalScore,
        //逐题评价（按题目顺序）
        List<QuestionEvalution> questionEvaluations,
        //优势
        List<String> strength,
        //改进建议
        List<String> improvements
) {
    public record QuestionEvalution(
           int score,
           String feedback
    ){

    }

}
