package interview.homegrown.modules.drill.web.dto;

/** 方向内某个知识点在前端的形态（带该用户已掌握层级）。 */
public record PlanConceptView(Long id, String name, String topic, int layer, int masteryLevel) {
}
