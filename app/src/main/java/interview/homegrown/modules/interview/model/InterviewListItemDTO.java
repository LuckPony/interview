package interview.homegrown.modules.interview.model;

import java.time.Instant;

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
        Instant createdAt,
        //面试方式：TEXT 文字 / VOICE 语音
        String mode
) {
}
