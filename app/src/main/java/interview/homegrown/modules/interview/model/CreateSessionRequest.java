package interview.homegrown.modules.interview.model;


import java.util.List;

/**
 * 创建会话请求
 * 面试依据 = 简历 + 学习方向（可多选），二选一必填其一（可都选）。
 * 都选时出题占比：简历 70% + 学习方向 30%。
 */
public record CreateSessionRequest(
        //面试方向（application.yml 的 skill，可空：方向由学习方向/简历决定）
        String skillId,
        //难度
        InterviewDifficulty difficulty,
        //主问题数量
        Integer questionCount,
        //关联简历ID（可选）
        Long resumeId,
        //学习方向ID（可多选，可选；与 resumeId 至少一个非空）
        List<Long> planIds,
        //面试方式：TEXT 文字 / VOICE 语音
        String mode,
        //LLM Provider
        String llmProvider
) {

}
