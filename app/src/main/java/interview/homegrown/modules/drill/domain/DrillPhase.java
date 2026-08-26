package interview.homegrown.modules.drill.domain;

/**
 * 三阶段练习（独立作答 → 教学讲解 → 迁移测试）的阶段标记。
 *
 * <p>FIRST_ANSWER：题目已出，等待用户第一次独立作答（阶段1判分锁定基础档位）；
 * TUTORING：阶段1已判分，AI 讲解 + 用户自由提问（阶段2，不评分）；
 * TRANSFER_TEST：阶段3，AI 结合已掌握知识点出新题考察，答对可降级通过（升级封顶 GOOD）；
 * DONE：全部阶段结束，run 置 GRADED。
 *
 * <p>与 drill_run.status 的关系：status 仍是外层状态机（READY/ANSWERING/GRADED…），
 * phase 是练习流程内部的细分阶段，两者独立推进。
 */
public enum DrillPhase {
    FIRST_ANSWER,
    TUTORING,
    TRANSFER_TEST,
    DONE
}
