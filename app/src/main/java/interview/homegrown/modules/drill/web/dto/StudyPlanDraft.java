package interview.homegrown.modules.drill.web.dto;

import java.util.List;

/** 对话收敛出的学习规划草案。draft 为 null 表示信息还不够、继续聊。 */
public record StudyPlanDraft(String title, String goal, List<PlanPoint> points, Long corpusId) {
}
