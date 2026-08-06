package interview.homegrown.modules.interview.model;


//创建会话请求
public record CreateSessionRequest(
        //面试方向
        String skillId,
        //难度
        InterviewDifficulty difficulty,
        //主问题数量
        Integer questionCount,
        //关联简历ID
        Long resumeId,
        //LLM Provider
        String llmProvider
) {

}
