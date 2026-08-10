package interview.homegrown.modules.drill.grader;

import java.util.List;

/**
 * 统一判分格式（每概念一块）：
 * { conceptId, role(PRIMARY/ANCHOR), pointResults[], extraCorrect[], factualErrors[] }
 * 序列化后存入 grade_result.by_concept。单点题只有一块；组合题（arity≥2）按 PRIMARY/ANCHOR 产出多块。
 */
public record ByConcept(Long conceptId, String role, List<PointVerdict> pointResults,
                        List<String> extraCorrect, List<String> factualErrors) {
}
