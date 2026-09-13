package interview.homegrown.modules.interview.model;


import java.util.List;

/**
 * 创建会话请求
 * 简历、学习方向（可多选）、知识库资料至少提供一种，可组合。
 * 有知识库时以其内容为主要范围；否则简历与学习方向按原有权重出题。
 */
public record CreateSessionRequest(
        //面试方向（application.yml 的 skill，可空：方向由学习方向/简历决定）
        String skillId,
        //面试轮次（兼容枚举：JUNIOR=一面、MIDDLE=二面、SENIOR=三面）
        InterviewDifficulty difficulty,
        //历史兼容字段；当前主问题数量固定为 8
        Integer questionCount,
        //关联简历ID（可选）
        Long resumeId,
        //学习方向ID（可多选、可选）
        List<Long> planIds,
        //面试方式：TEXT 文字 / VOICE 语音
        String mode,
        //LLM Provider
        String llmProvider,
        Long corpusId
) {

}
