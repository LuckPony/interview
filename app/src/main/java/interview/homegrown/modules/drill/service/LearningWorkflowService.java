package interview.homegrown.modules.drill.service;

import interview.homegrown.modules.drill.ai.LessonGenerator;
import interview.homegrown.modules.drill.domain.Concept;
import interview.homegrown.modules.drill.domain.DrillPurpose;
import interview.homegrown.modules.drill.domain.DrillRun;
import interview.homegrown.modules.drill.domain.DrillRunStatus;
import interview.homegrown.modules.drill.domain.StudyPlan;
import interview.homegrown.modules.drill.repository.ConceptRepository;
import interview.homegrown.modules.drill.repository.DrillRunRepository;
import interview.homegrown.modules.drill.repository.GradeResultRepository;
import interview.homegrown.modules.drill.repository.QuestionBankRepository;
import interview.homegrown.modules.drill.repository.StudyPlanRepository;
import interview.homegrown.modules.drill.web.dto.LearningNextView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static interview.homegrown.modules.drill.grader.GradeScale.PASS_LINE;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/** 统一学习路线编排：层级→知识点→子知识点→知识点综合检测→层级综合检测。 */
@Service
public class LearningWorkflowService {

    public static final int CONCEPT_ASSESSMENT_QUESTIONS = 2;
    public static final int LEVEL_ASSESSMENT_QUESTIONS = 3;

    private final StudyPlanRepository planRepo;
    private final ConceptRepository conceptRepo;
    private final DrillRunRepository runRepo;
    private final GradeResultRepository gradeRepo;
    private final QuestionBankRepository questionRepo;
    private final LessonGenerator lessonGenerator;
    private final ProgressContextService progressContext;

    public LearningWorkflowService(StudyPlanRepository planRepo, ConceptRepository conceptRepo,
                                   DrillRunRepository runRepo, GradeResultRepository gradeRepo,
                                   QuestionBankRepository questionRepo,
                                   LessonGenerator lessonGenerator,
                                   ProgressContextService progressContext) {
        this.planRepo = planRepo;
        this.conceptRepo = conceptRepo;
        this.runRepo = runRepo;
        this.gradeRepo = gradeRepo;
        this.questionRepo = questionRepo;
        this.lessonGenerator = lessonGenerator;
        this.progressContext = progressContext;
    }

    @Transactional
    public LearningNextView next(Long userId, Long planId) {
        StudyPlan plan = planRepo.findById(planId)
                .filter(p -> p.getUserId().equals(userId))
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "学习方向不存在"));
        List<Concept> all = conceptRepo.findByStudyPlanId(planId).stream()
                .sorted(Comparator.comparingInt(Concept::getLayer).thenComparing(Concept::getId))
                .toList();
        if (all.isEmpty()) return complete(plan, 1, "该方向还没有知识点");

        Set<String> passedKeys = runRepo.findPassedFocusedRuns(userId, DrillRunStatus.GRADED, PASS_LINE).stream()
                .map(r -> primaryConceptId(r) + "\u0000" + r.getFocusSubPoint())
                .collect(Collectors.toSet());

        for (int layer : all.stream().map(Concept::getLayer).distinct().sorted().toList()) {
            List<Concept> layerConcepts = all.stream().filter(c -> c.getLayer() == layer).toList();
            for (Concept concept : layerConcepts) {
                List<String> subs = ensureOutline(userId, concept);
                for (int i = 0; i < subs.size(); i++) {
                    if (!passedKeys.contains(concept.getId() + "\u0000" + subs.get(i))) {
                        return new LearningNextView(planId, plan.getTitle(), "SUB_POINT", layer,
                                concept.getId(), concept.getName(), subs.get(i), i, subs.size(),
                                0, 0, "先学习并练习这个子知识点");
                    }
                }
                int conceptDone = (int) runRepo
                        .findByUserIdAndPlanIdAndPurposeAndAssessmentConceptIdAndStatus(
                                userId, planId, DrillPurpose.CONCEPT_ASSESSMENT, concept.getId(), DrillRunStatus.GRADED)
                        .stream().filter(this::assessmentPassed).count();
                if (conceptDone < CONCEPT_ASSESSMENT_QUESTIONS) {
                    return new LearningNextView(planId, plan.getTitle(), "CONCEPT_ASSESSMENT", layer,
                            concept.getId(), concept.getName(), null, -1, subs.size(), conceptDone,
                            CONCEPT_ASSESSMENT_QUESTIONS, "子知识点已完成，进行大知识点综合检测");
                }
            }
            int layerDone = (int) runRepo.findByUserIdAndPlanIdAndPurposeAndAssessmentLayerAndStatus(
                    userId, planId, DrillPurpose.LEVEL_ASSESSMENT, layer, DrillRunStatus.GRADED).stream()
                    .filter(this::assessmentPassed).count();
            if (layerDone < LEVEL_ASSESSMENT_QUESTIONS) {
                return new LearningNextView(planId, plan.getTitle(), "LEVEL_ASSESSMENT", layer,
                        null, "L" + layer + " 层级综合检测", null, -1, 0, layerDone,
                        LEVEL_ASSESSMENT_QUESTIONS, "本层知识点已完成，进行跨知识点综合检测");
            }
        }
        return complete(plan, all.getLast().getLayer(), "L1-L5 学习与综合检测均已完成");
    }

    private List<String> ensureOutline(Long userId, Concept concept) {
        List<String> subs = lessonGenerator.outlineFromJson(concept.getLessonOutline());
        if (!subs.isEmpty()) return subs;
        subs = lessonGenerator.decompose(concept, progressContext.contextFor(userId, concept.getId()));
        if (subs.isEmpty()) subs = List.of(concept.getName());
        String json = lessonGenerator.outlineToJson(subs);
        if (json != null) {
            concept.setLessonOutline(json);
            conceptRepo.save(concept);
        }
        return subs;
    }

    private boolean assessmentPassed(DrillRun run) {
        return gradeRepo.findByRunId(run.getId())
                .map(g -> g.getRawScore() != null && g.getRawScore().compareTo(PASS_LINE) >= 0)
                .orElse(false);
    }

    private Long primaryConceptId(DrillRun run) {
        if (run.getAssessmentConceptId() != null) return run.getAssessmentConceptId();
        return questionRepo.findById(run.getQuestionId())
                .map(q -> q.getConceptIds() != null && q.getConceptIds().length > 0
                        ? q.getConceptIds()[0].longValue() : -1L)
                .orElse(-1L);
    }

    private LearningNextView complete(StudyPlan p, int layer, String message) {
        return new LearningNextView(p.getId(), p.getTitle(), "COMPLETE", layer,
                null, null, null, -1, 0, 0, 0, message);
    }
}
