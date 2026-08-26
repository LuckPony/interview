package interview.homegrown.modules.drill.web.dto;

/** 今日任务一条：复习(REVIEW) / 新学(NEW)，含预生成的题目信息（READY 后 stem 非空）。 */
public record DailyTaskView(
        Long id,
        Long planId,
        String planTitle,
        String kind,          // REVIEW / NEW
        Long conceptId,
        String conceptName,
        int layer,
        String status,        // PENDING / READY / DONE
        Long questionId,
        String stem,          // 预生成题干（未生成时为 null）
        String probeType,
        String subPoint       // 复习任务聚焦的子知识点（null = 概念级）
) {
}
