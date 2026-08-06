package interview.homegrown.modules.interview.model;


import java.util.List;

//LLM出题的结构化输出
public record InterviewQuestionResult(
        List<InterviewQuestion> questions
) {

    //单个面试题目
    public record InterviewQuestion(
            String question,
            List<String> followups
    ){

    }
}
