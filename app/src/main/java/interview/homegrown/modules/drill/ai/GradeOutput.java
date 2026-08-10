package interview.homegrown.modules.drill.ai;

import interview.homegrown.modules.drill.grader.PointVerdict;

import java.util.ArrayList;
import java.util.List;

/**
 * 判分器结构化输出：按概念分组的逐点 verdict。
 * conceptIndex 与出题时的概念清单顺序一致（服务端校验越界）。
 * pointResults 是兼容降级通道：模型只给扁平列表时归到 index 0。
 *
 * <p>注意这里【故意】没有 score / summary / correctAnswer 字段：
 * 分数由服务端算（痛点 3），标准答案不外发（痛点 7）。schema 就是护栏。
 */
public class GradeOutput {

    public List<ConceptGrade> byConcept;
    public List<PointVerdict> pointResults;

    public static class ConceptGrade {
        public int conceptIndex;
        public List<PointVerdict> pointResults;
        public List<String> extraCorrect;
        public List<String> factualErrors;
    }

    public List<ConceptGrade> normalizedGroups() {
        if (byConcept != null && !byConcept.isEmpty()) {
            return byConcept;
        }
        ConceptGrade fallback = new ConceptGrade();
        fallback.conceptIndex = 0;
        fallback.pointResults = pointResults == null ? List.of() : pointResults;
        fallback.extraCorrect = List.of();
        fallback.factualErrors = List.of();
        List<ConceptGrade> one = new ArrayList<>();
        one.add(fallback);
        return one;
    }
}
