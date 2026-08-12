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
 *   <li><b>NEW</b>：本方向内未掌握概念按 layer 升序（L1 先行），封顶 {@link #NEW_PER_PLAN}；</li>
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

    private final DailyTaskRepository taskRepo;
    private final StudyPlanRepository planRepo;
    private final ConceptRepository conceptRepo;
    private final MasteryRepository masteryRepo;
    private final QuestionBankRepository qbRepo;
    private final SelectionService selectionService;
    private final QuestionService questionService;
    private final CorpusService corpusService;

    private final ExecutorService generatorPool;

    public DailyPlanService(DailyTaskRepository taskRepo, StudyPlanRepository planRepo,
                            ConceptRepository conceptRepo, MasteryRepository masteryRepo,
                            QuestionBankRepository qbRepo, SelectionService selectionService,
                            QuestionService questionService, CorpusService corpusService) {
        this.taskRepo = taskRepo;
        this.planRepo = planRepo;
        this.conceptRepo = conceptRepo;
        this.masteryRepo = masteryRepo;
        this.qbRepo = qbRepo;
        this.selectionService = selectionService;
        this.questionService = questionService;
        this.corpusService = corpusService;
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
            QuestionBank qb = questionService.generate(sel, ref);
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

    /** 新学候选：本方向未掌握概念按 layer 升序（L1 先行，循序渐进），封顶 cap */
    private List<Concept> pickNew(Long userId, Long planId, int cap) {
        Set<Long> masteredIds = masteryRepo.findByUserId(userId).stream()
                .filter(m -> m.getMasteryLevel() > 0)
                .map(Mastery::getConceptId).collect(Collectors.toSet());
        return conceptRepo.findByStudyPlanId(planId).stream()
                .filter(c -> !masteredIds.contains(c.getId()))
                .sorted(Comparator.comparingInt(Concept::getLayer).thenComparing(Concept::getId))
                .limit(cap)
                .toList();
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
                QuestionBank qb = questionService.generate(sel, ref);
                t.setQuestionId(qb.getId());
                t.setStatus(DailyTask.STATUS_READY);
                taskRepo.save(t);
            } catch (Exception e) {
                log.warn("预生成题目失败 taskId={}: {}", taskId, e.getMessage());
            }
        });
    }
}
