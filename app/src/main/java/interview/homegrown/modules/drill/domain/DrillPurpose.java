package interview.homegrown.modules.drill.domain;

/** 一次练习在学习工作流中的用途。问答、追问、评分仍复用同一套 DrillRun 流程。 */
public enum DrillPurpose {
    SUB_POINT_PRACTICE,
    REVIEW,
    CONCEPT_ASSESSMENT,
    LEVEL_ASSESSMENT,
    /** 用户主动点「按整个知识点出题」：范围 = 已学内容 + 整个知识点（不进工作流检测计数）。 */
    CONCEPT_PRACTICE,
    /** 用户主动点「整层出题」：范围 = 已学内容 + 整个层级（不进工作流检测计数）。 */
    LAYER_PRACTICE,
    FREE_PRACTICE
}
