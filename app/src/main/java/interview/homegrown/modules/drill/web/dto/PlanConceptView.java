package interview.homegrown.modules.drill.web.dto;

/** 方向内某个知识点在前端的形态；子知识点达标与大知识点 mastery 独立统计。 */
public record PlanConceptView(Long id, String name, String topic, int layer, int masteryLevel,
                              String note, java.util.List<String> subPoints,
                              java.util.List<String> completedSubPoints) {
}
