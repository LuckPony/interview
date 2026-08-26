package interview.homegrown.modules.drill.service;

import interview.homegrown.modules.drill.ai.LessonGenerator;
import interview.homegrown.modules.drill.domain.Concept;
import interview.homegrown.modules.drill.domain.DrillRun;
import interview.homegrown.modules.drill.domain.DrillRunStatus;
import interview.homegrown.modules.drill.domain.Mastery;
import interview.homegrown.modules.drill.domain.SelectedTask;
import interview.homegrown.modules.drill.domain.SubPointPass;
import interview.homegrown.modules.drill.repository.ConceptRepository;
import interview.homegrown.modules.drill.repository.DrillRunRepository;
import interview.homegrown.modules.drill.repository.MasteryRepository;
import interview.homegrown.modules.drill.repository.QuestionBankRepository;
import interview.homegrown.modules.drill.repository.SubPointPassRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static interview.homegrown.modules.drill.grader.GradeScale.PASS_LINE;

/**
 * 选题闸门（纯确定性，零 LLM）。
 * 第一段：定 PRIMARY 概念 —— ① 到期复习 -> ② 从未练过按 layer 升序打基础 -> ③ 最近要到期的。
 * 第二段：交给 {@link CombinationPolicy} 决定 arity 与合法伙伴（ANCHOR）。
 * 这一步是"决策权收回服务端"的最前端体现：学什么、几个点一起练，由算法定，不由 LLM。
 */
@Service
public class SelectionService {

    private final ConceptRepository conceptRepo;
    private final MasteryRepository masteryRepo;
    private final CombinationPolicy combinationPolicy;
    private final LessonGenerator lessonGenerator;
    private final DrillRunRepository runRepo;
    private final SubPointPassRepository subPointPassRepo;
    private final QuestionBankRepository qbRepo;

    public SelectionService(ConceptRepository conceptRepo, MasteryRepository masteryRepo,
                            CombinationPolicy combinationPolicy, LessonGenerator lessonGenerator,
                            DrillRunRepository runRepo, SubPointPassRepository subPointPassRepo,
                            QuestionBankRepository qbRepo) {
        this.conceptRepo = conceptRepo;
        this.masteryRepo = masteryRepo;
        this.combinationPolicy = combinationPolicy;
        this.lessonGenerator = lessonGenerator;
        this.runRepo = runRepo;
        this.subPointPassRepo = subPointPassRepo;
        this.qbRepo = qbRepo;
    }

    public SelectedTask pickNext(Long userId) {
        List<Mastery> mastered = masteryRepo.findByUserId(userId);
        Map<Long, Mastery> byConcept = mastered.stream()
                .collect(Collectors.toMap(Mastery::getConceptId, Function.identity(), (a, b) -> a));
        List<Concept> all = conceptRepo.findAll();
        if (all.isEmpty()) {
            throw new IllegalStateException("concept 表为空，请先导入知识矩阵");
        }

        Concept primary = pickPrimary(all, mastered, byConcept.keySet());
        return combinationPolicy.build(primary, all, byConcept);
    }

    /** 指定概念开练（REHEARSAL 用）：跳过优先级排序，但仍走组合策略 */
    public SelectedTask pickFor(Long userId, Long conceptId) {
        List<Mastery> mastered = masteryRepo.findByUserId(userId);
        Map<Long, Mastery> byConcept = mastered.stream()
                .collect(Collectors.toMap(Mastery::getConceptId, Function.identity(), (a, b) -> a));
        Concept primary = conceptRepo.findById(conceptId)
                .orElseThrow(() -> new IllegalArgumentException("concept 不存在: " + conceptId));
        return combinationPolicy.build(primary, conceptRepo.findAll(), byConcept);
    }

    /**
     * 继续学习（痛点1「方向」级入口）：候选池限定在某 study_plan 的概念集合内，
     * 仍走同一套确定性优先级（到期复习 -> 未练过按 layer -> 最近到期）。
     * 关键点：把 mastered / all 都收窄到本方向，否则 pickPrimary 的「到期复习」会挑到别的方向的概念。
     */
    public SelectedTask pickNextWithinPlan(Long userId, Long planId) {
        List<Concept> planConcepts = conceptRepo.findByStudyPlanId(planId);
        if (planConcepts.isEmpty()) {
            throw new IllegalArgumentException("该方向还没有知识点，先去练「系统帮我选」或新建方向");
        }
        List<Mastery> mastered = masteryRepo.findByUserId(userId);
        Set<Long> planIds = planConcepts.stream().map(Concept::getId).collect(Collectors.toSet());
        List<Mastery> planMastered = mastered.stream()
                .filter(m -> planIds.contains(m.getConceptId())).toList();
        Set<Long> doneIds = planMastered.stream().map(Mastery::getConceptId).collect(Collectors.toSet());
        Concept primary = pickPrimary(planConcepts, planMastered, doneIds);
        Map<Long, Mastery> byConcept = planMastered.stream()
                .collect(Collectors.toMap(Mastery::getConceptId, Function.identity(), (a, b) -> a));
        return combinationPolicy.build(primary, planConcepts, byConcept);
    }

    /**
     * 复习（痛点1「方向」级入口）：只挑本方向内「已掌握(level>0)且到期(dueAt<=now)」的概念，
     * 按 dueAt 升序取最早一个直接开练。无到期项则明确抛错，由前端提示而不是静默退化。
     */
    public SelectedTask pickReviewWithinPlan(Long userId, Long planId) {
        List<Concept> planConcepts = conceptRepo.findByStudyPlanId(planId);
        Set<Long> planIds = planConcepts.stream().map(Concept::getId).collect(Collectors.toSet());
        Instant now = Instant.now();
        List<Mastery> due = masteryRepo.findByUserId(userId).stream()
                .filter(m -> planIds.contains(m.getConceptId()))
                .filter(m -> m.getMasteryLevel() > 0)
                .filter(m -> m.getDueAt() != null && !m.getDueAt().isAfter(now))
                .sorted(Comparator.comparing(m -> m.getDueAt() == null ? Instant.EPOCH : m.getDueAt()))
                .toList();
        if (due.isEmpty()) {
            throw new IllegalStateException("该方向暂无到期复习项，先去「继续学习」补基础");
        }
        return pickFor(userId, due.get(0).getConceptId());
    }

    /**
     * 层级练习（痛点1「方向」级入口）：候选池限定在「本方向 + 指定 layer」的概念集合内，
     * 仍走同一套确定性优先级（到期复习 -> 从未练过 -> 最近到期）。
     * 用于学习计划页「点某个层级直接开练」——例如只想补 L1 基础或冲 L4 串联。
     */
    public SelectedTask pickNextWithinPlanAtLayer(Long userId, Long planId, int layer) {
        List<Concept> planConcepts = conceptRepo.findByStudyPlanId(planId);
        List<Concept> layerConcepts = planConcepts.stream()
                .filter(c -> c.getLayer() == layer)
                .toList();
        if (layerConcepts.isEmpty()) {
            throw new IllegalStateException("该方向还没有 L" + layer + " 层级的概念，先去「开始练习」让系统补建");
        }
        List<Mastery> mastered = masteryRepo.findByUserId(userId);
        Set<Long> layerIds = layerConcepts.stream().map(Concept::getId).collect(Collectors.toSet());
        List<Mastery> layerMastered = mastered.stream()
                .filter(m -> layerIds.contains(m.getConceptId()))
                .toList();
        Set<Long> doneIds = layerMastered.stream().map(Mastery::getConceptId).collect(Collectors.toSet());
        Concept primary = pickPrimary(layerConcepts, layerMastered, doneIds);
        Map<Long, Mastery> byConcept = layerMastered.stream()
                .collect(Collectors.toMap(Mastery::getConceptId, Function.identity(), (a, b) -> a));
        return combinationPolicy.build(primary, layerConcepts, byConcept);
    }

    /**
     * 复习聚焦（需求1：复习也基于子知识点）：取该概念下第一个「未通过」的子知识点
     * （判分通过 ∪ 手动直通 之外）。概念没有拆子点或全部通过 → 返回 null（概念级复习）。
     * 方向级复习（start-plan review）与每日任务复习（REVIEW）共用。
     */
    public String pickReviewSubPoint(Long userId, Long conceptId) {
        Concept concept = conceptRepo.findById(conceptId).orElse(null);
        if (concept == null) return null;
        List<String> subs = lessonGenerator.outlineFromJson(concept.getLessonOutline());
        if (subs.isEmpty()) return null;
        Set<String> passedKeys = runRepo
                .findPassedFocusedRuns(userId, DrillRunStatus.GRADED, PASS_LINE).stream()
                .filter(r -> questionContainsConcept(r.getQuestionId(), conceptId))
                .map(DrillRun::getFocusSubPoint)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        subPointPassRepo.findByUserId(userId).stream()
                .filter(p -> conceptId.equals(p.getConceptId()))
                .map(SubPointPass::getSubPoint)
                .forEach(passedKeys::add);
        return subs.stream().filter(s -> !passedKeys.contains(s)).findFirst().orElse(null);
    }

    private boolean questionContainsConcept(Long questionId, Long conceptId) {
        return qbRepo.findById(questionId)
                .map(q -> q.getConceptIds() != null
                        && java.util.Arrays.stream(q.getConceptIds()).anyMatch(cid -> conceptId.equals(cid.longValue())))
                .orElse(false);
    }

    private Concept pickPrimary(List<Concept> all, List<Mastery> mastered, Set<Long> doneIds) {
        Instant now = Instant.now();

        // ① 到期复习
        Optional<Mastery> due = mastered.stream()
                .filter(m -> m.getDueAt() != null && !m.getDueAt().isAfter(now))
                .min(Comparator.comparing(m -> m.getDueAt() == null ? Instant.EPOCH : m.getDueAt()));
        if (due.isPresent()) {
            return findConcept(all, due.get().getConceptId());
        }

        // ② 从未练过，按 layer 升序（先打 L1 基础）
        Optional<Concept> fresh = all.stream()
                .filter(c -> !doneIds.contains(c.getId()))
                .min(Comparator.comparingInt(Concept::getLayer));
        if (fresh.isPresent()) {
            return fresh.get();
        }

        // ③ 都练过且未到期：选最近要到期的
        Optional<Mastery> soon = mastered.stream()
                .filter(m -> m.getDueAt() != null)
                .min(Comparator.comparing(Mastery::getDueAt));
        return soon.map(m -> findConcept(all, m.getConceptId())).orElse(all.get(0));
    }

    private Concept findConcept(List<Concept> all, Long id) {
        return all.stream().filter(c -> c.getId().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("concept 不存在: " + id));
    }
}
