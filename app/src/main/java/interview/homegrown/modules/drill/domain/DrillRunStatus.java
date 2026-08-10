package interview.homegrown.modules.drill.domain;

/**
 * drill_run 状态机。靠状态推进而非索引。
 * READY -> ANSWERING -> SUBMITTED -> GRADED；READY/ANSWERING 为"未闭环"，
 * 受部分唯一索引物理闸门约束（同一用户同时只能有一个）。
 * 72h 无活动自动转 PARKED（由调度任务处理）。
 */
public enum DrillRunStatus {
    READY,
    ANSWERING,
    SUBMITTED,
    GRADED,
    PARKED
}
