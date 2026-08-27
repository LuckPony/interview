package interview.homegrown.modules.drill.domain;

/**
 * 苏格拉底教学流程状态（取代旧三阶段 FIRST_ANSWER/TUTORING/TRANSFER_TEST）。
 *
 * <p>ANSWERING：用户作答中（含苏格拉底引导循环，AI 每轮判 answering/needs_guide/done）；
 * GUIDED：G1 未达标，已进入引导（正在或已引导，待再考查）；
 * DONE：本题结束（G1 达标直接结束 / G2 评分后结束 / 看答案封 AGAIN 结束）。
 *
 * <p>与 drill_run.status 的关系：status 是外层状态机（READY/ANSWERING/GRADED…），
 * socraticState 是练习流程内部细分阶段，两者独立推进。
 */
public enum DrillPhase {
    ANSWERING,
    GUIDED,
    DONE
}
