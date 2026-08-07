package interview.homegrown.modules.interview.model;


import java.time.LocalDateTime;
import java.util.List;

//面试会话详情DTO，创建会话的时候同时把题目出好
public record InterviewSessionDTO(
        String id,
        String skillId,
        String skillName,
        InterviewDifficulty difficulty,
        InterviewStatus status,
        int totalQuestions,
        int currentQuestionIndex,
        Integer totalScore,
        String llmProviders,
        LocalDateTime createdAt,
        List<InterviewAnswerEntity> answers
) {
}
