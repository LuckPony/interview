package interview.homegrown.modules.drill.web.dto;

import java.util.List;

/** GET /study-plan 的返回：一个方向 + 它聚合的知识点 + 精熟计数 + 待复习数 + 绑定资料名。 */
public record PlanView(Long id, String title, String goal,
                       List<PlanConceptView> concepts, int masteredCount, int totalCount,
                       int dueReviewCount, String corpusName) {
}
