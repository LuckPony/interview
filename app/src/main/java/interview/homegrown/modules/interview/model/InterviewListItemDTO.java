package interview.homegrown.modules.interview.model;

import java.time.LocalDateTime;

//面试历史列表项DTO
public record InterviewListItemDTO(
        String id,
        String skillId,
        String skillName,
        InterviewDifficulty difficulty,
        InterviewStatus status,
        int totalQuestions,
        int answeredCount,
        Integer totalScore,
        LocalDateTime createdAt
) {
}
