package interview.homegrown.modules.drill.service;

import interview.homegrown.modules.drill.domain.Concept;
import interview.homegrown.modules.drill.domain.DailyTask;
import interview.homegrown.modules.drill.domain.Mastery;
import interview.homegrown.modules.drill.domain.QuestionBank;
import interview.homegrown.modules.drill.domain.SelectedTask;
import interview.homegrown.modules.drill.domain.StudyPlan;
import interview.homegrown.modules.drill.repository.ConceptRepository;
import interview.homegrown.modules.drill.repository.DailyTaskRepository;
import interview.homegrown.modules.drill.repository.MasteryRepository;
import interview.homegrown.modules.drill.repository.QuestionBankRepository;
import interview.homegrown.modules.drill.repository.StudyPlanRepository;
import interview.homegrown.modules.drill.web.dto.DailyTaskView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * 每日学习任务排程：让学习方向"自主安排每天复习多少、新学多少"，并异步预生成题目。
 *
 * <p>规则（循序渐进）：
 * <ul>
 *   <li><b>REVIEW</b>：本方向内到期（dueAt&lt;=now）且已掌握的概念，按到期时间升序，封顶 {@link #REVIEW_CAP}；</li>
 *   <li><b>NEW</b>：按「层掌握率递进解锁」选可出层，等概率抽层，封顶 {@link #NEW_PER_PLAN}。
 *       层掌握率 = 该层内写达标（mastery_level&gt;=2）的概念数 / 该层概念总数；
 *       L1 达标 50% 才解锁 L2，L2 达标 50% 才解锁 L3，以此类推——一开始 L1 没掌握就只出 L1 的题，
 *       用户手动指定层级（「练这一层」）不受此门槛限制。</li>
 *   <li>落表 PENDING → 线程池异步出题（复用 {@link QuestionService} 去重/跨知识点逻辑）→ READY。</li>
 * </ul>
 *
 * <p>触发：{@link #generateAll()} 每天 06:30 全员预生成；{@link #ensureToday(Long)} 懒兜底（用户访问今日任务/复盘时
 * 若今天没生成则现场生成，不依赖定时器恰好跑过）。
 */
@Service
public class DailyPlanService {

    private static final Logger log = LoggerFactory.getLogger(DailyPlanService.class);

    /** 每个方向每天新学的概念数 */
    private static final int NEW_PER_PLAN = 3;
    /** 每个方向每天复习（到期）的概念数上限 */
    private static final int REVIEW_CAP = 8;

    /** 层解锁门槛：上一层掌握率达标比例（L1 达标 50% 才解锁 L2，以此类推） */
    private static final double LAYER_UNLOCK_RATIO = 0.5;
    /** 写达标线：mastery_level >= 2 才算该层掌握 */
    private static final int MASTERED_LEVEL = 2;

    private final DailyTaskRepository taskRepo;
    private final StudyPlanRepository planRepo;
    private final ConceptRepository conceptRepo;
    private final MasteryRepository masteryRepo;
    private final QuestionBankRepository qbRepo;
    private final SelectionService selectionService;
    private final QuestionService questionService;
    private final CorpusService corpusService;
    private final ProgressContextService progressContext;

    private final ExecutorService generatorPool;

    public DailyPlanService(DailyTaskRepository taskRepo, StudyPlanRepository planRepo,
                            ConceptRepository conceptRepo, MasteryRepository masteryRepo,
                            QuestionBankRepository qbRepo, SelectionService selectionService,
                            QuestionService questionService, CorpusService corpusService,
                            ProgressContextService progressContext) {
        this.taskRepo = taskRepo;
        this.planRepo = planRepo;
        this.conceptRepo = conceptRepo;
        this.masteryRepo = masteryRepo;
        this.qbRepo = qbRepo;
        this.selectionService = selectionService;
        this.questionService = questionService;
        this.corpusService = corpusService;
        this.progressContext = progressContext;
        this.generatorPool = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "daily-task-gen");
            t.setDaemon(true);
            return t;
        });
    }

    /** 定时：每天 06:30 为所有用户预生成今日任务（主动推送的承载——打开即 READY，无需等待）。 */
    @Scheduled(cron = "0 30 6 * * *")
    public void generateAll() {
        for (Long uid : planRepo.findDistinctUserIds()) {
            try {
                ensureToday(uid);
            } catch (Exception e) {
                log.warn("定时生成今日任务失败 uid={}: {}", uid, e.getMessage());
            }
        }
    }

    /**
     * 幂等：今天已生成过的方向不再重复建；但<b>每个方向都保证有今日任务</b>——
     * 某个方向若是今天后建的（首访问时还没生成），也要当场补上，否则该方向今日任务为空。
     * PENDING 未出题则异步补生成。返回今日任务。
     */
    @Transactional
    public List<DailyTask> ensureToday(Long userId) {
        LocalDate today = LocalDate.now();
        List<DailyTask> existing = taskRepo.findByUserIdAndTaskDate(userId, today);
        List<DailyTask> created = new ArrayList<>();
        for (StudyPlan plan : planRepo.findByUserId(userId)) {
            boolean hasTasks = existing.stream().anyMatch(t -> plan.getId().equals(t.getPlanId()));
            if (!hasTasks) {
                created.addAll(planTasks(userId, plan, today));
            }
        }
        if (!created.isEmpty()) {
            try {
                existing = new ArrayList<>(existing);
                existing.addAll(taskRepo.saveAll(created));
            } catch (DataIntegrityViolationException e) {
                // 并发下重复生成：直接读回已存在的
                existing = taskRepo.findByUserIdAndTaskDate(userId, today);
            }
        }
        for (DailyTask t : existing) {
            if (DailyTask.STATUS_PENDING.equals(t.getStatus()) && t.getQuestionId() == null) {
                generateAsync(t.getId());
            }
        }
        return existing;
    }

    /** 今日任务视图（含方向名、概念名、预生成题干）。 */
    @Transactional(readOnly = true)
    public List<DailyTaskView> todayView(Long userId) {
        List<DailyTask> tasks = taskRepo.findByUserIdAndTaskDate(userId, LocalDate.now());
        if (tasks.isEmpty()) {
            return List.of();
        }
        Map<Long, String> planTitles = planRepo.findByUserId(userId).stream()
                .collect(Collectors.toMap(StudyPlan::getId, StudyPlan::getTitle));
        List<Concept> concepts = conceptRepo.findAllById(
                tasks.stream().map(DailyTask::getConceptId).toList());
        Map<Long, Concept> byConcept = concepts.stream()
                .collect(Collectors.toMap(Concept::getId, c -> c));

        List<DailyTaskView> views = new ArrayList<>();
        for (DailyTask t : tasks) {
            Concept c = byConcept.get(t.getConceptId());
            String stem = null;
            String probe = null;
            if (t.getQuestionId() != null) {
                QuestionBank qb = qbRepo.findById(t.getQuestionId()).orElse(null);
                if (qb != null) {
                    stem = qb.getStem();
                    probe = qb.getProbeType().name();
                }
            }
            views.add(new DailyTaskView(t.getId(), t.getPlanId(),
                    planTitles.getOrDefault(t.getPlanId(), "全局"),
                    t.getKind(), t.getConceptId(),
                    c == null ? "概念" : c.getName(),
                    c == null ? 0 : c.getLayer(),
                    t.getStatus(), t.getQuestionId(), stem, probe));
        }
        return views;
    }

    /** 取当前用户的一条任务（不存在 404）。 */
    @Transactional(readOnly = true)
    public DailyTask requireTask(Long userId, Long taskId) {
        return taskRepo.findById(taskId)
                .filter(t -> t.getUserId().equals(userId))
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "今日任务不存在"));
    }

    /** 任务已作答：置 DONE。 */
    @Transactional
    public void markDone(Long taskId) {
        taskRepo.findById(taskId).ifPresent(t -> {
            t.setStatus(DailyTask.STATUS_DONE);
            taskRepo.save(t);
        });
    }

    /** 今日 READY 任务（按 id 升序，尚未作答的）。 */
    @Transactional(readOnly = true)
    public List<DailyTask> readyTasksToday(Long userId) {
        return taskRepo.findByUserIdAndTaskDateAndStatusInOrderByIdAsc(
                userId, LocalDate.now(), List.of(DailyTask.STATUS_READY));
    }

    /** 同步兜底出题：预生成还没好（PENDING）时现场出一题，保证任务可立即开练。 */
    @Transactional
    public boolean ensureReady(Long userId, Long taskId) {
        DailyTask t = requireTask(userId, taskId);
        if (t.getQuestionId() == null) {
            SelectedTask sel = selectionService.pickFor(userId, t.getConceptId());
            String ref = corpusService.referenceForConcept(t.getConceptId());
            String context = progressContext.contextFor(t.getUserId(), sel.conceptIds());
            QuestionBank qb = questionService.generate(sel, context == null ? ref : context);
            t.setQuestionId(qb.getId());
            t.setStatus(DailyTask.STATUS_READY);
            taskRepo.save(t);
        }
        return t.getQuestionId() != null;
    }

    // ------------------------------------------------------------ 内部

    private List<DailyTask> planTasks(Long userId, StudyPlan plan, LocalDate today) {
        List<DailyTask> out = new ArrayList<>();
        for (Concept c : pickReview(userId, plan.getId(), REVIEW_CAP)) {
            out.add(newTask(userId, plan.getId(), today, DailyTask.KIND_REVIEW, c.getId()));
        }
        for (Concept c : pickNew(userId, plan.getId(), NEW_PER_PLAN)) {
            out.add(newTask(userId, plan.getId(), today, DailyTask.KIND_NEW, c.getId()));
        }
        return out;
    }

    private DailyTask newTask(Long userId, Long planId, LocalDate today, String kind, Long conceptId) {
        DailyTask t = new DailyTask();
        t.setUserId(userId);
        t.setPlanId(planId);
        t.setTaskDate(today);
        t.setKind(kind);
        t.setConceptId(conceptId);
        return t;
    }

    /** 复习候选：本方向到期且已掌握的概念，按到期时间升序，封顶 cap */
    private List<Concept> pickReview(Long userId, Long planId, int cap) {
        Set<Long> planIds = conceptRepo.findByStudyPlanId(planId).stream()
                .map(Concept::getId).collect(Collectors.toSet());
        Instant now = Instant.now();
        return masteryRepo.findByUserId(userId).stream()
                .filter(m -> planIds.contains(m.getConceptId()))
                .filter(m -> m.getMasteryLevel() > 0)
                .filter(m -> m.getDueAt() != null && !m.getDueAt().isAfter(now))
                .sorted(Comparator.comparing(Mastery::getDueAt))
                .limit(cap)
                .map(m -> conceptRepo.findById(m.getConceptId()).orElse(null))
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 新学候选：按「层掌握率递进解锁」选可出层，等概率抽层，层内按出现顺序取未掌握概念，封顶 cap。
     *
     * <p>解锁链：层 L 可出，当且仅当它之下<b>所有存在</b>的层掌握率都 &gt;= 50%
     * （层掌握率 = 写达标概念数 / 该层概念总数）。若某层没有未掌握概念，则该层不参与抽层；
     * 可出层全空则本次不新学（不强凑）。随机种子含 (userId, planId, 日期)，同一天结果可复现。
     * 包级可见：供单测直接验证。
     */
    List<Concept> pickNew(Long userId, Long planId, int cap) {
        List<Concept> all = conceptRepo.findByStudyPlanId(planId);
        if (all.isEmpty()) {
            return List.of();
        }
        List<Mastery> mastery = masteryRepo.findByUserId(userId);
        Set<Long> masteredIds = mastery.stream()
                .filter(m -> m.getMasteryLevel() > 0)
                .map(Mastery::getConceptId).collect(Collectors.toSet());

        // 未掌握概念按层分组（层内保持查询顺序 = 按 id）
        Map<Integer, List<Concept>> poolByLayer = all.stream()
                .filter(c -> !masteredIds.contains(c.getId()))
                .collect(Collectors.groupingBy(Concept::getLayer));
        // 每层掌握率
        Map<Integer, Double> ratioByLayer = all.stream()
                .collect(Collectors.groupingBy(Concept::getLayer, Collectors.toList()))
                .entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey,
                        e -> layerRatio(mastery, e.getValue())));
        List<Integer> layerAsc = ratioByLayer.keySet().stream().sorted().toList();

        // 解锁链：层 L 可出 ⇔ 它之下所有存在的层掌握率都 >= 50%，且本层还有未掌握概念
        List<Integer> available = new ArrayList<>();
        for (int i = 0; i < layerAsc.size(); i++) {
            boolean gated = false;
            for (int j = 0; j < i; j++) {
                if (ratioByLayer.get(layerAsc.get(j)) < LAYER_UNLOCK_RATIO) {
                    gated = true;
                    break;
                }
            }
            if (!gated && !poolByLayer.getOrDefault(layerAsc.get(i), List.of()).isEmpty()) {
                available.add(layerAsc.get(i));
            }
        }
        if (available.isEmpty()) {
            return List.of();
        }

        // 每个名额独立等概率抽层（可出 L1/L2 → 各 50%；L1/L2/L3 → 各 1/3），层内按 id 顺序取
        Random rng = new Random(seed(userId, planId, LocalDate.now()));
        List<Concept> out = new ArrayList<>();
        while (out.size() < cap) {
            List<Integer> still = available.stream()
                    .filter(l -> !poolByLayer.get(l).isEmpty())
                    .toList();
            if (still.isEmpty()) break;
            int layer = still.get(rng.nextInt(still.size()));
            out.add(poolByLayer.get(layer).remove(0));
        }
        return out;
    }

    /** 层掌握率：写达标（level>=2）概念数 / 该层概念总数；空层视为 100%（不阻塞下层解锁） */
    private double layerRatio(List<Mastery> mastery, List<Concept> concepts) {
        if (concepts.isEmpty()) return 1.0;
        Set<Long> ids = concepts.stream().map(Concept::getId).collect(Collectors.toSet());
        long mastered = mastery.stream()
                .filter(m -> ids.contains(m.getConceptId()) && m.getMasteryLevel() >= MASTERED_LEVEL)
                .count();
        return (double) mastered / concepts.size();
    }

    /** 同一天结果可复现（含日期），便于排查今日任务为何这样排 */
    private long seed(Long userId, Long planId, LocalDate date) {
        return userId * 1_000_003L + planId * 31L + date.toEpochDay();
    }

    /**
     * 异步预出题：READY 后回填 question_id。失败保持 PENDING，下次 ensureToday 重试。
     * 线程内重新加载实体，避免跨线程改已分离实体。
     */
    private void generateAsync(Long taskId) {
        generatorPool.submit(() -> {
            try {
                DailyTask t = taskRepo.findById(taskId).orElse(null);
                if (t == null || t.getQuestionId() != null) return;
                SelectedTask sel = selectionService.pickFor(t.getUserId(), t.getConceptId());
                String ref = corpusService.referenceForConcept(t.getConceptId());
                String context = progressContext.contextFor(t.getUserId(), sel.conceptIds());
                QuestionBank qb = questionService.generate(sel, context == null ? ref : context);
                t.setQuestionId(qb.getId());
                t.setStatus(DailyTask.STATUS_READY);
                taskRepo.save(t);
            } catch (Exception e) {
                log.warn("预生成题目失败 taskId={}: {}", taskId, e.getMessage());
            }
        });
    }
}
