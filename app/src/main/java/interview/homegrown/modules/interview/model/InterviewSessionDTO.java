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
        List<InterviewAnswerEntity> answers,
        //面试方式：TEXT 文字 / VOICE 语音
        String mode,
        //关联学习方向 ID（逗号分隔，可空）
        String planIds,
        //评估结果（解析自 evaluation_json，未评估时为 null）
        InterviewEvaluationResult evaluation
) {
}
