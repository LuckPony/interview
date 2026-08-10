package interview.homegrown.modules.drill.domain;

/** 用户产出答案的形态。这是"题型"被消解的关键维度——Grader 按它分策略。 */
public enum ResponseFormat {
    FREE_TEXT,   // 自由文本（LLM 逐点判分）
    CHOICE,      // 选择题（精确比对，不走 LLM）
    STRUCTURED,  // 结构化字段（按 schema 逐条 rubric）
    CODE         // 代码（relay 力扣判题，不走 LLM）
}
