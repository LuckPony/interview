package interview.homegrown.modules.drill.grader;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.homegrown.modules.drill.ai.GeneratedQuestion;
import interview.homegrown.modules.drill.ai.GradeGenerator;
import interview.homegrown.modules.drill.ai.GradeOutput;
import interview.homegrown.modules.drill.domain.Concept;
import interview.homegrown.modules.drill.domain.ConceptRole;
import interview.homegrown.modules.drill.domain.DrillRun;
import interview.homegrown.modules.drill.domain.Grade;
import interview.homegrown.modules.drill.domain.QuestionBank;
import interview.homegrown.modules.drill.repository.ConceptRepository;
import interview.homegrown.modules.drill.repository.DrillRunRepository;
import interview.homegrown.modules.drill.service.ProgressContextService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * FREE_TEXT / STRUCTURED 判分：LLM 逐点判 HIT/PARTIAL/MISS（分类任务），分数由本类计算。
 *
 * <p>组合题（arity&gt;=2）在这里按 conceptIndex 拆回每个概念的子分：
 * index 0 恒为 PRIMARY，其余为 ANCHOR。拆不开就没法 per concept 更新掌握度。
 *
 * <p>绝不把"算分"交给 LLM：它有善意脑补倾向，答错也能圆出高分，画像会漂移。
 */
@Component
public class GraderText implements Grader {

    private static final Logger log = LoggerFactory.getLogger(GraderText.class);

    private final GradeGenerator gradeGenerator;
    private final ConceptRepository conceptRepo;
    private final ObjectMapper objectMapper;
    private final DrillRunRepository runRepo;
    private final ProgressContextService progressContext;

    public GraderText(GradeGenerator gradeGenerator, ConceptRepository conceptRepo,
                      ObjectMapper objectMapper, DrillRunRepository runRepo,
                      ProgressContextService progressContext) {
        this.gradeGenerator = gradeGenerator;
        this.conceptRepo = conceptRepo;
        this.objectMapper = objectMapper;
        this.runRepo = runRepo;
        this.progressContext = progressContext;
    }

    @Override
    public GraderOutput grade(Long runId, QuestionBank q, String rawAnswer, boolean timed) {
        return gradeWithConversation(runId, q, rawAnswer, timed, null);
    }

    /** 多轮对话判分：传入老师实际问过的问题实录，未被问到的评分点判 NA（不计分） */
    public GraderOutput gradeWithConversation(Long runId, QuestionBank q, String rawAnswer,
                                              boolean timed, String conversation) {
        // 学习上下文：run → userId → 概念 → 画像/资料块/互联网（判分依据，不额外搜索）
        String context = null;
        DrillRun run = runRepo.findById(runId).orElse(null);
        if (run != null && q.getConceptIds() != null && q.getConceptIds().length > 0) {
            java.util.List<Long> ids = java.util.Arrays.stream(q.getConceptIds())
                    .map(Integer::longValue).toList();
            context = progressContext.contextFor(run.getUserId(), ids);
        }
        return gradeRaw(q.getStem(), q.getPointsJson(), q.getConceptIds(), rawAnswer, timed, context, conversation);
    }

    /**
     * 可复用入口：REHEARSAL 的追问不在 question_bank 里，直接传 stem + pointsJson 进来判。
     *
     * @param conceptIds 顺序即 conceptIndex 顺序，index 0 为 PRIMARY
     */
    public GraderOutput gradeRaw(String stem, String pointsJson, Integer[] conceptIds,
                                 String rawAnswer, boolean timed) {
        return gradeRaw(stem, pointsJson, conceptIds, rawAnswer, timed, null);
    }

    public GraderOutput gradeRaw(String stem, String pointsJson, Integer[] conceptIds,
                                 String rawAnswer, boolean timed, String context) {
        return gradeRaw(stem, pointsJson, conceptIds, rawAnswer, timed, context, null);
    }

    public GraderOutput gradeRaw(String stem, String pointsJson, Integer[] conceptIds,
                                 String rawAnswer, boolean timed, String context, String conversation) {
        GeneratedQuestion gq = parseQuestion(pointsJson);
        List<GeneratedQuestion.ConceptPoints> groups = gq.normalizedGroups();
        Map<Long, String> nameById = loadNames(conceptIds);

        List<GradeGenerator.ConceptPointGroup> toGrade = new ArrayList<>();
        for (GeneratedQuestion.ConceptPoints g : groups) {
            Long cid = conceptIdAt(conceptIds, g.conceptIndex);
            List<String> texts = g.points == null ? List.<String>of()
                    : g.points.stream().map(GeneratedQuestion.Point::text).toList();
            toGrade.add(new GradeGenerator.ConceptPointGroup(
                    g.conceptIndex, nameById.getOrDefault(cid, "概念" + g.conceptIndex), texts));
        }

        GradeOutput out = gradeGenerator.grade(stem, rawAnswer, toGrade, context, conversation,
                gq.followups == null ? List.of() : gq.followups);

        List<ByConcept> byConcepts = new ArrayList<>();
        List<ConceptScore> conceptScores = new ArrayList<>();
        List<PointVerdict> allVerdicts = new ArrayList<>();

        // 评分点按概念分组 → 取每组自己的 points（含 weight），供加权计分时按点文本对齐。
        Map<Integer, GeneratedQuestion.ConceptPoints> groupsByIndex = gq.normalizedGroups().stream()
                .collect(Collectors.toMap(g -> g.conceptIndex, g -> g, (a, b) -> a));

        for (GradeOutput.ConceptGrade cg : out.normalizedGroups()) {
            int idx = clampIndex(cg.conceptIndex, conceptIds.length);
            Long cid = conceptIdAt(conceptIds, idx);
            ConceptRole role = idx == 0 ? ConceptRole.PRIMARY : ConceptRole.ANCHOR;
            // 只展示真正参与本次计分的评分点；同时防御模型输出 "na" / " NA " 等格式差异。
            // 老题可能把 followup 扩展内容错误塞进 points，判分器会将其标为 NA，因此前端隐藏。
            List<PointVerdict> verdicts = cg.pointResults == null ? List.of() : cg.pointResults.stream()
                    .filter(v -> v.verdict() == null || !"NA".equalsIgnoreCase(v.verdict().trim()))
                    .toList();

            byConcepts.add(new ByConcept(cid, role.name(), verdicts,
                    nullToEmpty(cg.extraCorrect), nullToEmpty(cg.factualErrors)));

            // 该概念分组的评分点（含 weight）：加权计分；未匹配到权重则退化为等权
            GeneratedQuestion.ConceptPoints cpg = groupsByIndex.get(cg.conceptIndex);
            List<GeneratedQuestion.Point> conceptPoints = (cpg == null || cpg.points == null)
                    ? List.of() : cpg.points;
            BigDecimal sub = GradeScale.scoreWeighted(verdicts, conceptPoints);
            conceptScores.add(new ConceptScore(cid, role, sub, GradeScale.toGrade(sub, timed)));
            allVerdicts.addAll(verdicts);
        }

        BigDecimal rawScore = GradeScale.scoreWeighted(allVerdicts, gq.allPoints());
        Grade grade = GradeScale.toGrade(rawScore, timed);
        return new GraderOutput(serialize(byConcepts), rawScore, grade, conceptScores);
    }

    private GeneratedQuestion parseQuestion(String json) {
        if (json == null || json.isBlank()) {
            return new GeneratedQuestion();
        }
        try {
            return objectMapper.readValue(json, GeneratedQuestion.class);
        } catch (Exception e) {
            log.warn("题目 points 反序列化失败，按空评分点处理", e);
            return new GeneratedQuestion();
        }
    }

    private Map<Long, String> loadNames(Integer[] conceptIds) {
        List<Long> ids = new ArrayList<>();
        for (Integer i : conceptIds) {
            ids.add(i.longValue());
        }
        return conceptRepo.findAllById(ids).stream()
                .collect(Collectors.toMap(Concept::getId, Concept::getName, (a, b) -> a, HashMap::new));
    }

    /** 模型可能给出越界 index，钳到合法范围，绝不让它污染 concept 映射 */
    private int clampIndex(int idx, int size) {
        if (idx < 0 || idx >= size) {
            log.warn("判分返回越界 conceptIndex={}（size={}），已钳到 0", idx, size);
            return 0;
        }
        return idx;
    }

    private Long conceptIdAt(Integer[] conceptIds, int idx) {
        int safe = (idx < 0 || idx >= conceptIds.length) ? 0 : idx;
        return conceptIds[safe].longValue();
    }

    private List<String> nullToEmpty(List<String> in) {
        return in == null ? List.of() : in;
    }

    private String serialize(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("判分结果序列化失败", e);
        }
    }

    /** 供 REHEARSAL 复用：把一轮追问的评分点包成 question_bank 相同的存储形态 */
    public String wrapPoints(GeneratedQuestion gq) {
        return serialize(gq);
    }
}
