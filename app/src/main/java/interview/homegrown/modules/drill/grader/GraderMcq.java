package interview.homegrown.modules.drill.grader;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.homegrown.modules.drill.ai.McqOption;
import interview.homegrown.modules.drill.domain.ConceptRole;
import interview.homegrown.modules.drill.domain.Grade;
import interview.homegrown.modules.drill.domain.QuestionBank;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * CHOICE 判分：精确比对用户所选与正确选项，**完全不走 LLM**。
 * 客观可复现，是痛点 3 诚实画像的基石。
 */
@Component
public class GraderMcq implements Grader {

    private final ObjectMapper objectMapper;

    public GraderMcq(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public GraderOutput grade(Long runId, QuestionBank q, String rawAnswer, boolean timed) {
        List<McqOption> opts = parseOptions(q.getMcqOptionsJson());
        Set<String> chosen = parseSelected(rawAnswer);

        List<PointVerdict> results = new ArrayList<>();
        int hit = 0;
        for (var o : opts) {
            boolean selected = chosen.contains(o.key());
            boolean ok = (o.correct() == selected);   // 该选的选了、不该选的没选 = 正确
            if (ok) hit++;
            results.add(new PointVerdict(o.key() + ":" + o.text(), ok ? "HIT" : "MISS", rawAnswer));
        }

        // MCQ 天然是单概念摸底题（arity=1），index 0 即 PRIMARY
        Long conceptId = Long.valueOf(q.getConceptIds()[0]);
        ByConcept bc = new ByConcept(conceptId, ConceptRole.PRIMARY.name(), results, List.of(), List.of());
        String byConceptJson = serialize(List.of(bc));

        BigDecimal rawScore = opts.isEmpty() ? BigDecimal.ZERO :
                BigDecimal.valueOf((double) hit / opts.size() * 100).setScale(2, RoundingMode.HALF_UP);
        Grade grade = GradeScale.toGrade(rawScore, timed);
        List<ConceptScore> scores = List.of(new ConceptScore(conceptId, ConceptRole.PRIMARY, rawScore, grade));
        return new GraderOutput(byConceptJson, rawScore, grade, scores);
    }

    private List<McqOption> parseOptions(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            McqOption[] arr = objectMapper.readValue(json, McqOption[].class);
            return List.of(arr);
        } catch (Exception e) {
            return List.of();
        }
    }

    // 用户所选：逗号/空格/分号/顿号分割，取字母键（A/B/C...）
    private Set<String> parseSelected(String raw) {
        Set<String> set = new HashSet<>();
        if (raw == null) return set;
        for (String part : raw.split("[,\\s;、]+")) {
            String t = part.trim().toUpperCase();
            if (!t.isEmpty()) set.add(t);
        }
        return set;
    }

    private String serialize(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("判分结果序列化失败", e);
        }
    }
}
