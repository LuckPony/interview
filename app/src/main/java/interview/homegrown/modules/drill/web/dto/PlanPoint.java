package interview.homegrown.modules.drill.web.dto;

/** 规划里的一个知识点。layer 由 LLM 建议、服务端 clamp 到 [1,5]。 */
public record PlanPoint(String name, int layer, String note) {
}
