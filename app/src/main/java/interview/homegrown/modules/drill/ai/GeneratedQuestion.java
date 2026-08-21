package interview.homegrown.modules.drill.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * 出题器结构化输出：题干 + 按概念分组的评分点。
 *
 * <p>修订 8 的落点：单点题与组合题<b>共用同一结构</b>，区别只在 byConcept 有几块。
 * conceptIndex 是 0-based 序号，对应服务端传给 LLM 的概念清单顺序 —— 故意不让 LLM 写
 * 真实 conceptId，避免它编造主键。服务端做越界检查后再映射回真实 id。
 *
 * <p>points 字段是兼容降级通道：模型偷懒只给扁平列表时，全部归给 PRIMARY。
 */
public class GeneratedQuestion {

    public String stem;
    /** 主问之外的更深入小问（2-4 条）。逐条在对话里追问，绝不堆进 stem。 */
    public List<String> followups;
    public List<ConceptPoints> byConcept;
    public List<Point> points;

    public static class ConceptPoints {
        public int conceptIndex;
        public List<Point> points;
    }

    public record Point(String text, double weight) {
    }

    /** 归一化：无论模型给哪种形态，统一成 byConcept 分组（缺失则整体归 index 0） */
    public List<ConceptPoints> normalizedGroups() {
        if (byConcept != null && !byConcept.isEmpty()) {
            return byConcept;
        }
        ConceptPoints fallback = new ConceptPoints();
        fallback.conceptIndex = 0;
        fallback.points = points == null ? List.of() : points;
        List<ConceptPoints> one = new ArrayList<>();
        one.add(fallback);
        return one;
    }

    /** 全部评分点的扁平视图（算总分用） */
    public List<Point> allPoints() {
        List<Point> flat = new ArrayList<>();
        for (ConceptPoints g : normalizedGroups()) {
            if (g.points != null) flat.addAll(g.points);
        }
        return flat;
    }
}
