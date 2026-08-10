package interview.homegrown.modules.drill.grader;

/** 单点判分结果。verdict ∈ HIT/PARTIAL/MISS；evidence 必填且为用户原话片段（逐字）。 */
public record PointVerdict(String point, String verdict, String evidence) {
}
