package interview.homegrown.modules.drill.grader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/** byConceptJson 解析工具：从判分结果 JSON 提取薄弱点评分点。 */
public final class ByConceptJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ByConceptJson() {
    }

    /** 判分结果里没打中的评分点（MISS/PARTIAL），作为"薄弱点"给复盘/笔记页 */
    public static List<String> extractWeakPoints(String byConceptJson) {
        if (byConceptJson == null || byConceptJson.isBlank()) return List.of();
        try {
            JsonNode root = MAPPER.readTree(byConceptJson);
            if (!root.isArray()) return List.of();
            List<String> weak = new ArrayList<>();
            for (JsonNode concept : root) {
                JsonNode prs = concept.path("pointResults");
                if (!prs.isArray()) continue;
                for (JsonNode p : prs) {
                    String verdict = p.path("verdict").asText("").toUpperCase();
                    if ("MISS".equals(verdict) || "PARTIAL".equals(verdict)) {
                        String point = p.path("point").asText("");
                        if (!point.isBlank()) weak.add(point);
                    }
                }
            }
            return weak;
        } catch (Exception e) {
            return List.of();
        }
    }
}
