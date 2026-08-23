package interview.homegrown.modules.drill.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.homegrown.modules.drill.ai.LessonGenerator;
import interview.homegrown.modules.drill.ai.TutorGenerator;
import interview.homegrown.modules.drill.domain.*;
import interview.homegrown.modules.drill.repository.*;
import interview.homegrown.modules.drill.service.*;
import interview.homegrown.modules.drill.web.dto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static interview.homegrown.modules.drill.grader.GradeScale.PASS_LINE;
import static org.springframework.http.HttpStatus.*;

/**
 * 学习模块唯一 REST 入口。所有端点需 JWT（见 SecurityConfig.drillSecurityFilterChain）。
 *
 * <p>三条主线：
 * <ul>
 *   <li><b>LEARN</b>：POST /next -&gt; POST /{runId}/submit -&gt; POST /{runId}/note</li>
 *   <li><b>REHEARSAL</b>：POST /rehearsal/start -&gt; POST /rehearsal/{runId}/answer（可能多轮）</li>
 *   <li><b>看数</b>：GET /profile（深度画像） GET /debt（内化欠账）</li>
 * </ul>
 *
 * <p>注意 /next 前置了两道闸门：内化债务（痛点 7）与未闭环作答唯一索引（痛点 5）。
 * 闸门失败一律 409，不是 400 —— 这不是参数错，是"你现在不该拿新题"的状态冲突。
 */
@RestController
@RequestMapping("/api/drill")
public class DrillController {

    private static final Logger log = LoggerFactory.getLogger(DrillController.class);

    private final SelectionService selectionService;
    private final QuestionService questionService;
    private final AnswerService answerService;
    private final ProfileService profileService;
    private final RehearsalService rehearsalService;
    private final NoteService noteService;
    private final HistoryService historyService;
    private final RecordCleanupService recordCleanupService;
    private final CorpusService corpusService;
    private final ProgressContextService progressContext;
    private final DrillRunRepository runRepo;
    private final QuestionBankRepository questionBankRepo;
    private final DrillTurnRepository turnRepo;
    private final ConceptRepository conceptRepo;
    private final ConceptLessonRepository conceptLessonRepo;
    private final TutorGenerator tutorGenerator;
    private final LessonGenerator lessonGenerator;
    private final ObjectMapper objectMapper;
    private final DailyPlanService dailyPlanService;
    private final LearningWorkflowService learningWorkflowService;
    private final ReviewService reviewService;

    public DrillController(SelectionService selectionService, QuestionService questionService,
                           AnswerService answerService, ProfileService profileService,
                           RehearsalService rehearsalService, NoteService noteService,
                           HistoryService historyService, RecordCleanupService recordCleanupService,
                           CorpusService corpusService, ProgressContextService progressContext,
                           DrillRunRepository runRepo, QuestionBankRepository questionBankRepo,
                           DrillTurnRepository turnRepo, ConceptRepository conceptRepo,
                           ConceptLessonRepository conceptLessonRepo,
                           TutorGenerator tutorGenerator, LessonGenerator lessonGenerator,
                           ObjectMapper objectMapper,
                           DailyPlanService dailyPlanService, LearningWorkflowService learningWorkflowService,
                           ReviewService reviewService) {
        this.selectionService = selectionService;
        this.questionService = questionService;
        this.answerService = answerService;
        this.profileService = profileService;
        this.rehearsalService = rehearsalService;
        this.noteService = noteService;
        this.recordCleanupService = recordCleanupService;
        this.historyService = historyService;
        this.corpusService = corpusService;
        this.progressContext = progressContext;
        this.runRepo = runRepo;
        this.questionBankRepo = questionBankRepo;
        this.turnRepo = turnRepo;
        this.conceptRepo = conceptRepo;
        this.conceptLessonRepo = conceptLessonRepo;
        this.tutorGenerator = tutorGenerator;
        this.lessonGenerator = lessonGenerator;
        this.objectMapper = objectMapper;
        this.dailyPlanService = dailyPlanService;
        this.learningWorkflowService = learningWorkflowService;
        this.reviewService = reviewService;
    }

    // ------------------------------------------------------------ LEARN

    @PostMapping("/next")
    public QuestionView next() {
        Long uid = currentUserId();
        // 债务闸门已按用户决策移除：欠账不拦截开新题（欠账清单仍经 GET /debt 暴露，供复盘页自选消化）
        var task = selectionService.pickNext(uid);         // 确定性选题（服务端）
        return openRun(uid, task);
    }

    /** 学习计划的确定性下一步：严格按层级、概念、子点、综合检测推进。 */
    @GetMapping("/learning-next/{planId}")
    public LearningNextView learningNext(@PathVariable Long planId) {
        return learningWorkflowService.next(currentUserId(), planId);
    }

    /** 用户自选概念开练（痛点1：用户掌 what，服务端仍用 pickFor 定 task）。 */
    @PostMapping("/start")
    public QuestionView start(@RequestBody StartRequest req) {
        Long uid = currentUserId();
        // 债务闸门已移除（同上）
        var task = selectionService.pickFor(uid, req.conceptId());
        QuestionView view = openRun(uid, task, planIdOfConcept(req.conceptId()), null, req.conceptId(), req.subPoint());
        tagRun(view.runId(), req.subPoint() == null ? DrillPurpose.FREE_PRACTICE : DrillPurpose.SUB_POINT_PRACTICE,
                planIdOfConcept(req.conceptId()), req.conceptId(), null);
        return view;
    }

    /** 方向级入口：继续（plan 内确定性选题）/ 复习（plan 内到期已掌握项）/ 层级练习（指定 layer）。 */
    @PostMapping("/start-plan")
    public QuestionView startPlan(@RequestBody StartPlanRequest req) {
        Long uid = currentUserId();
        // 债务闸门已移除（同上）
        String mode = req.mode() == null ? "continue" : req.mode();
        if ("concept-assessment".equals(mode) || "level-assessment".equals(mode)) {
            return openAssessmentRun(uid, req.planId(), mode, req.layer(), req.conceptId());
        }
        SelectedTask task = switch (mode) {
            case "review" -> selectionService.pickReviewWithinPlan(uid, req.planId());
            case "layer" -> selectionService.pickNextWithinPlanAtLayer(uid, req.planId(), req.layer());
            default -> selectionService.pickNextWithinPlan(uid, req.planId());
        };
        Integer targetLayer = "layer".equals(mode) ? req.layer() : null;
        Long targetConcept = null;
        QuestionView view = openRun(uid, task, req.planId(), targetLayer, targetConcept, null);
        DrillPurpose purpose = "review".equals(mode) ? DrillPurpose.REVIEW : DrillPurpose.FREE_PRACTICE;
        tagRun(view.runId(), purpose, req.planId(), req.conceptId(), req.layer());
        return view;
    }

    /**
     * 历史记录「继续练习」：复用原题（同 questionId）开一条新 run。
     * 旧 run 保持 GRADED 不可变（历史留档），新 run 从 READY 重新作答。
     *
     * <p>债务闸门已整体移除（用户 2026-08-10 决策：欠账不拦截开新题），本接口同样不设。
     * 复用 openRun 的 active-run 语义：若已有未闭环作答则直接恢复之（物理闸门语义）。
     */
    @PostMapping("/restart")
    public QuestionView restart(@RequestBody RestartRequest req) {
        Long uid = currentUserId();
        // 闸门一在此有意跳过——见 Javadoc
        if (req.runId() == null) {
            throw new ResponseStatusException(BAD_REQUEST, "runId 不能为空");
        }
        DrillRun old = runRepo.findByUserIdAndId(uid, req.runId())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "记录不存在"));
        QuestionBank q = questionBankRepo.findById(old.getQuestionId())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "题目已失效"));

        // 已有未闭环作答 -> 恢复之（物理闸门语义：同一时间只做一道）。
        // 复用只认 LEARN 主线：若活跃的是 REHEARSAL，先搁置再开原题，避免把它当 LEARN 新题返回。
        List<DrillRun> activeLearns = runRepo.findByUserIdAndStatusInAndMode(uid, ACTIVE_STATUSES, DrillMode.LEARN);
        if (!activeLearns.isEmpty()) {
            DrillRun run = activeLearns.getFirst();
            QuestionBank aq = questionBankRepo.findById(run.getQuestionId())
                    .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "题目已失效"));
            return new QuestionView(run.getId(), run.getQuestionId(), aq.getStem(), aq.getProbeType().name(), aq.getResponseFormat().name());
        }
        List<DrillRun> activeRehearsals = runRepo.findByUserIdAndStatusInAndMode(uid, ACTIVE_STATUSES, DrillMode.REHEARSAL);
        if (!activeRehearsals.isEmpty()) {
            DrillRun stray = activeRehearsals.getFirst();
            stray.setStatus(DrillRunStatus.PARKED);
            runRepo.save(stray);
        }

        // 用原题开新 run：不重新调用 LLM，直接复用已生成的 stem/points
        DrillRun run = new DrillRun();
        run.setUserId(uid);
        run.setQuestionId(q.getId());
        run.setMode(DrillMode.LEARN);
        run.setStatus(DrillRunStatus.READY);
        try {
            run = runRepo.save(run);
        } catch (DataIntegrityViolationException e) {
            // 闸门二：部分唯一索引物理闸门（并发兜底）
            throw new ResponseStatusException(CONFLICT, "已有未完成的作答，请先完成或搁置");
        }
        return new QuestionView(run.getId(), q.getId(), q.getStem(), q.getProbeType().name(), q.getResponseFormat().name());
    }

    private QuestionView openAssessmentRun(Long uid, Long planId, String mode, Integer layer, Long conceptId) {
        // 综合检测必须新开对应范围的题，不能被同方向另一条普通子点题“恢复”并冒充检测题。
        for (DrillRun active : runRepo.findByUserIdAndStatusInAndMode(uid, ACTIVE_STATUSES, DrillMode.LEARN)) {
            active.setStatus(DrillRunStatus.PARKED);
            runRepo.save(active);
        }
        List<Concept> scope;
        DrillPurpose purpose;
        if ("concept-assessment".equals(mode)) {
            Concept target = conceptRepo.findById(conceptId)
                    .filter(c -> planId.equals(c.getStudyPlanId()))
                    .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "综合检测知识点不存在"));
            scope = List.of(target);
            layer = target.getLayer();
            purpose = DrillPurpose.CONCEPT_ASSESSMENT;
        } else {
            final int targetLayer = layer == null ? 1 : layer;
            List<Concept> layerPool = conceptRepo.findByStudyPlanId(planId).stream()
                    .filter(c -> c.getLayer() == targetLayer)
                    .sorted(Comparator.comparing(Concept::getId))
                    .toList();
            int already = runRepo.findByUserIdAndPlanIdAndPurposeAndAssessmentLayerAndStatus(
                    uid, planId, DrillPurpose.LEVEL_ASSESSMENT, targetLayer, DrillRunStatus.GRADED).size();
            int take = Math.min(3, layerPool.size());
            scope = java.util.stream.IntStream.range(0, take)
                    .mapToObj(i -> layerPool.get((already + i) % layerPool.size()))
                    .toList();
            if (scope.isEmpty()) throw new ResponseStatusException(NOT_FOUND, "该层没有可检测的知识点");
            layer = targetLayer;
            purpose = DrillPurpose.LEVEL_ASSESSMENT;
        }
        List<ConceptRef> refs = java.util.stream.IntStream.range(0, scope.size())
                .mapToObj(i -> ConceptRef.of(scope.get(i), i == 0 ? ConceptRole.PRIMARY : ConceptRole.ANCHOR))
                .toList();
        SelectedTask task = new SelectedTask(refs);
        String context = progressContext.contextFor(uid, task.conceptIds());
        context = (context == null ? "" : context + "\n\n")
                + (purpose == DrillPurpose.CONCEPT_ASSESSMENT
                ? "这是大知识点综合检测。题目应综合该知识点下多个子知识点，考察组合运用，不要只重复单个定义。"
                : "这是 L" + layer + " 层级综合检测。题目应在当前层的多个知识点间建立真实工程联系，难度不得超过当前层级。")
                + "一次只出一道核心主问，后续仍使用普通聊天式作答。";
        QuestionBank q = questionService.generate(task, context);
        QuestionView view = openRunOnQuestion(uid, q.getId(), planId, null);
        tagRun(view.runId(), purpose, planId, conceptId, layer);
        return view;
    }

    private void tagRun(Long runId, DrillPurpose purpose, Long planId, Long conceptId, Integer layer) {
        runRepo.findById(runId).ifPresent(run -> {
            run.setPurpose(purpose);
            run.setPlanId(planId);
            run.setAssessmentConceptId(conceptId);
            run.setAssessmentLayer(layer);
            runRepo.save(run);
        });
    }

    // ------------------------------------------------------------ 先教后考（拆解 + 子知识点讲解）

    /** 拆解知识点为子知识点清单（缓存 concept.lesson_outline；无缓存则现场拆解后写回）。 */
    @PostMapping("/{conceptId}/outline")
    public OutlineView outline(@PathVariable Long conceptId) {
        Long uid = currentUserId();
        Concept concept = conceptRepo.findById(conceptId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "知识点不存在"));

        List<String> subPoints = lessonGenerator.outlineFromJson(concept.getLessonOutline());
        boolean cached = !subPoints.isEmpty();
        if (!cached) {
            String context = progressContext.contextFor(uid, conceptId);
            subPoints = lessonGenerator.decompose(concept, context);
            if (subPoints.isEmpty()) {
                subPoints = List.of(concept.getName());   // 降级：概念本身作为一个子点
            }
            String json = lessonGenerator.outlineToJson(subPoints);
            if (json != null) {
                concept.setLessonOutline(json);
                conceptRepo.save(concept);
            }
        }
        List<String> completedSubPoints = runRepo
                .findPassedFocusedRuns(uid, DrillRunStatus.GRADED, PASS_LINE).stream()
                .filter(r -> questionContainsConcept(r.getQuestionId(), conceptId))
                .map(DrillRun::getFocusSubPoint)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        return new OutlineView(conceptId, concept.getName(), concept.getTopic(), subPoints, completedSubPoints, cached);
    }

    /** 子知识点讲解 SSE 流（缓存 concept_lesson；无缓存则流式生成后写回）。 */
    @PostMapping(value = "/{conceptId}/lesson", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> lesson(@PathVariable Long conceptId,
                                                        @RequestParam String subPoint) {
        Long uid = currentUserId();
        Concept concept = conceptRepo.findById(conceptId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "知识点不存在"));
        final String sub = subPoint.trim();
        if (sub.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "subPoint 不能为空");
        }

        // 缓存命中：直接整体下发（一个 data 帧）；未命中则流式生成后写回。
        final ConceptLesson cachedLesson = conceptLessonRepo
                .findByConceptIdAndSubPoint(conceptId, sub).orElse(null);
        final String context = progressContext.contextFor(uid, conceptId);

        StreamingResponseBody body = out -> {
            try {
                out.write("event: start\ndata: {}\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();

                if (cachedLesson != null) {
                    String cachedText = cachedLesson.getLessonText();
                    String normalized = lessonGenerator.normalizeLesson(cachedText);
                    if (!normalized.equals(cachedText == null ? "" : cachedText.trim())) {
                        // 旧缓存可能保存了模型“讲完后从第 1 节重新开始”的重复尾段，读取时自动修复。
                        cachedLesson.setLessonText(normalized);
                        cachedLesson.setCharCount(normalized.length());
                        conceptLessonRepo.save(cachedLesson);
                    }
                    out.write(("data: {\"text\":\"" + jsonEscape(normalized) + "\"}\n\n")
                            .getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } else {
                    final StringBuilder buf = new StringBuilder();
                    String full = lessonGenerator.streamLesson(concept, sub, context,
                            token -> {
                                buf.append(token);
                                try {
                                    out.write(("data: {\"text\":\"" + jsonEscape(token) + "\"}\n\n")
                                            .getBytes(StandardCharsets.UTF_8));
                                    out.flush();
                                } catch (Exception e) {
                                    log.debug("lesson token 推送异常（已吞）: {}", e.getMessage());
                                }
                            },
                            r -> sseReasoning(out, r));

                    if (full != null && !full.isBlank()) {
                        ConceptLesson cl = new ConceptLesson();
                        cl.setConceptId(conceptId);
                        cl.setSubPoint(sub);
                        cl.setLessonText(full);
                        cl.setCharCount(full.length());
                        try {
                            conceptLessonRepo.save(cl);
                        } catch (Exception e) {
                            // 并发/重复插入撞唯一索引：忽略，已有缓存即可
                            log.debug("子知识点讲解缓存写回冲突（忽略）: {}", e.getMessage());
                        }
                    } else {
                        out.write(("data: {\"text\":\"" + jsonEscape("（讲解生成失败，可先点「开始做题」，之后再看判分讲解）") + "\"}\n\n")
                                .getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                }

                out.write("event: done\ndata: {}\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (Exception e) {
                log.warn("lesson-stream 推送异常", e);
                try {
                    out.write(("event: error\ndata: " + jsonEscape(e.getMessage()) + "\n\n")
                            .getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (Exception ignored) {
                }
            }
        };

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(body);
    }

    // ------------------------------------------------------------ 今日任务（每日自动排期）

    /** 今日任务：确保生成（懒兜底）后返回列表（含预生成题干，READY 即秒开）。 */
    @GetMapping("/today")
    public List<DailyTaskView> today() {
        Long uid = currentUserId();
        dailyPlanService.ensureToday(uid);
        return dailyPlanService.todayView(uid);
    }

    /**
     * 用今日任务的预生成题开 run（不调 LLM）。预生成还没好（PENDING）时现场同步出一题兜底。
     * 真正开了新 run 才把任务置 DONE（若是恢复别的活跃 run，则不消费该任务）。
     */
    @PostMapping("/task/{taskId}/start")
    public QuestionView startTask(@PathVariable Long taskId) {
        Long uid = currentUserId();
        dailyPlanService.ensureReady(uid, taskId);
        DailyTask t = dailyPlanService.requireTask(uid, taskId);
        // 记录开 run 前的活跃 run：只有本次真正开了新 run（而非恢复了旧的活跃作答）才消费任务，
        // 否则用户手头挂着别的未完成作答时（即使会被搁置）任务永远不置 DONE，导致已学的题反复出现。
        Set<Long> activeBefore = activeLearnIds(uid);
        QuestionView view = openRunOnQuestion(uid, t.getQuestionId(), t.getPlanId());
        if (!activeBefore.contains(view.runId())) {
            dailyPlanService.markDone(taskId);
        }
        return view;
    }

    @PostMapping("/task/{taskId}/done")
    public Map<String, Object> completeTask(@PathVariable Long taskId) {
        Long uid = currentUserId();
        dailyPlanService.requireTask(uid, taskId);
        dailyPlanService.markDone(taskId);
        return Map.of("ok", true);
    }

    /** 连续下一题：取今日下一个 READY 任务开 run；今日已全部完成则 404，由前端提示。 */
    @PostMapping("/next-task")
    public QuestionView nextTask() {
        Long uid = currentUserId();
        dailyPlanService.ensureToday(uid);
        List<DailyTask> ready = dailyPlanService.readyTasksToday(uid);
        if (ready.isEmpty()) {
            throw new ResponseStatusException(NOT_FOUND, "今日任务已全部完成，去自由练习吧");
        }
        DailyTask t = ready.get(0);
        dailyPlanService.ensureReady(uid, t.getId());
        t = dailyPlanService.requireTask(uid, t.getId());
        Set<Long> activeBefore = activeLearnIds(uid);
        QuestionView view = openRunOnQuestion(uid, t.getQuestionId(), t.getPlanId());
        if (!activeBefore.contains(view.runId())) {
            dailyPlanService.markDone(t.getId());
        }
        return view;
    }

    /** 当前用户所有活跃 LEARN run 的 id（READY / ANSWERING）。 */
    private Set<Long> activeLearnIds(Long uid) {
        return runRepo.findByUserIdAndStatusInAndMode(uid, ACTIVE_STATUSES, DrillMode.LEARN)
                .stream().map(DrillRun::getId).collect(Collectors.toSet());
    }

    private static final List<DrillRunStatus> ACTIVE_STATUSES = List.of(DrillRunStatus.READY, DrillRunStatus.ANSWERING);

    private QuestionView openRun(Long uid, SelectedTask task) {
        return openRun(uid, task, null, null, null, null);   // 自由模式：不指定目标，恢复任何活跃 run
    }

    private QuestionView openRun(Long uid, SelectedTask task, Long targetPlanId) {
        return openRun(uid, task, targetPlanId, null, null, null);
    }

    private QuestionView openRun(Long uid, SelectedTask task, Long targetPlanId,
                                 Integer targetLayer, Long targetConceptId, String focus) {
        QuestionView resumed = resumeActiveOrPark(uid, targetPlanId, targetLayer, targetConceptId);
        if (resumed != null) {
            if (focus == null || focus.isBlank()) return resumed;
            DrillRun active = runRepo.findById(resumed.runId()).orElse(null);
            if (active != null && focus.equals(active.getFocusSubPoint())) return resumed;
            // 同一大知识点下切换到另一个子知识点时，不能恢复上一子点的题。
            if (active != null) {
                active.setStatus(DrillRunStatus.PARKED);
                runRepo.save(active);
            }
        }

        // 学习上下文注入：学生进度 + 概念要点 + 用户资料块 + 互联网补充（素材不锁死）
        String context = progressContext.contextFor(uid, task.conceptIds());
        // 先教后考：出题限定到「子知识点」粒度，题目必须围绕当前子知识点，不跑偏到别的子点。
        if (focus != null && !focus.isBlank()) {
            context = (context == null ? "" : context + "\n\n")
                    + "本次练习聚焦的子知识点：「" + focus + "」。题目必须围绕这个子知识点展开，"
                    + "不要考这个概念下的其它子知识点。";
        }
        List<String> practicedStems = List.of();
        if (focus != null && !focus.isBlank()) {
            List<DrillRun> priorRuns = priorFocusedRuns(uid, task.conceptId(), focus);
            practicedStems = priorRuns.stream()
                    .map(r -> questionBankRepo.findById(r.getQuestionId()).map(QuestionBank::getStem).orElse(null))
                    .filter(s -> s != null && !s.isBlank())
                    .distinct()
                    .limit(5)
                    .toList();
            String dialogue = focusedPracticeContext(priorRuns);
            if (!dialogue.isBlank()) {
                context += "\n\n用户此前在这个子知识点的已完成练习对话：\n" + dialogue
                        + "\n请据此出一道真正的新题：避开已问过的题干、场景和直接结论；"
                        + "对已经正确回答的内容换认知动作或应用场景，对暴露出的薄弱处做针对性考察。";
            }
        }
        var q = questionService.generate(task, context, practicedStems);       // 出题（LLM 填空）
        return openRunOnQuestion(uid, q.getId(), targetPlanId, focus);
    }

    /** 取同一用户、同一概念、同一子点最近完成的练习，防止同名子点跨概念串线。 */
    private List<DrillRun> priorFocusedRuns(Long uid, Long conceptId, String focus) {
        return runRepo.findTop20ByUserIdAndFocusSubPointAndStatusOrderByIdDesc(
                        uid, focus, DrillRunStatus.GRADED).stream()
                .filter(r -> questionBankRepo.findById(r.getQuestionId())
                        .map(q -> java.util.Arrays.stream(q.getConceptIds())
                                .anyMatch(cid -> conceptId.equals(cid.longValue())))
                        .orElse(false))
                .limit(5)
                .toList();
    }

    /** 历史对话只用于个性化出题，限制长度避免持续练习后撑爆模型上下文。 */
    private String focusedPracticeContext(List<DrillRun> runs) {
        StringBuilder out = new StringBuilder();
        for (DrillRun run : runs) {
            QuestionBank q = questionBankRepo.findById(run.getQuestionId()).orElse(null);
            if (q == null) continue;
            out.append("题目：").append(truncateForPrompt(q.getStem(), 800)).append('\n');
            for (DrillTurn turn : turnRepo.findByRunIdOrderByRoundAsc(run.getId())) {
                if (turn.getRawAnswer() != null && !turn.getRawAnswer().isBlank()) {
                    out.append("用户：").append(truncateForPrompt(turn.getRawAnswer(), 500)).append('\n');
                }
                if (turn.getTutorText() != null && !turn.getTutorText().isBlank()) {
                    out.append("老师：").append(truncateForPrompt(turn.getTutorText(), 500)).append('\n');
                }
            }
            out.append('\n');
            if (out.length() >= 6000) break;
        }
        return truncateForPrompt(out.toString(), 6000);
    }

    private static String truncateForPrompt(String text, int max) {
        if (text == null || text.length() <= max) return text == null ? "" : text;
        return text.substring(0, max) + "…";
    }

    /**
     * 物理闸门（LEARN 主线复用 + 方向/层级/概念隔离）：
     * 有活跃 LEARN 且<b>与用户本次请求的目标一致</b>（同方向，且未显式指定层级/概念，或指定了且匹配）
     * → 恢复它，避免同一题开两条未闭环 run。
     * 活跃 run 与请求不匹配（别的方向、或用户显式点了别的层级/概念）→ 先搁置它，为请求的目标开新题。
     * 有活跃 REHEARSAL 先搁置腾出闸门名额。无活跃则返回 null，可开新题。
     */
    private QuestionView resumeActiveOrPark(Long uid, Long targetPlanId, Integer targetLayer, Long targetConceptId) {
        List<DrillRun> activeLearns = runRepo.findByUserIdAndStatusInAndMode(uid, ACTIVE_STATUSES, DrillMode.LEARN);
        if (!activeLearns.isEmpty()) {
            DrillRun run = activeLearns.getFirst();
            Long activePlanId = planIdOfQuestion(run.getQuestionId());
            boolean samePlan = targetPlanId == null || activePlanId == null
                    || targetPlanId.equals(activePlanId);
            if (samePlan && layerMatches(run, targetLayer) && conceptMatches(run, targetConceptId)) {
                QuestionBank q = questionBankRepo.findById(run.getQuestionId())
                        .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "题目已失效"));
                return new QuestionView(run.getId(), q.getId(), q.getStem(), q.getProbeType().name(), q.getResponseFormat().name());
            }
            // 活跃 run 与请求不匹配（别的方向，或显式指定的层级/概念不一致）：搁置（PARKED），为请求目标开新题
            run.setStatus(DrillRunStatus.PARKED);
            runRepo.save(run);
        }
        // 存在被丢弃的活跃 REHEARSAL（如中途切去练习）：先搁置腾出物理闸门名额，再开 LEARN 新题。
        // 用 PARKED（与 72h 自动搁置同款终态）而非 endRehearsal 结算——避免对未完成的模拟面试误判分/动 mastery。
        List<DrillRun> activeRehearsals = runRepo.findByUserIdAndStatusInAndMode(uid, ACTIVE_STATUSES, DrillMode.REHEARSAL);
        if (!activeRehearsals.isEmpty()) {
            DrillRun stray = activeRehearsals.getFirst();
            stray.setStatus(DrillRunStatus.PARKED);
            runRepo.save(stray);
        }
        return null;
    }

    /** 用已存在的题目开一条 LEARN run（不调 LLM，今日任务预生成题走这里）。 */
    private QuestionView openRunOnQuestion(Long uid, Long questionId) {
        return openRunOnQuestion(uid, questionId, null);
    }

    private QuestionView openRunOnQuestion(Long uid, Long questionId, Long targetPlanId) {
        return openRunOnQuestion(uid, questionId, targetPlanId, null);
    }

    private QuestionView openRunOnQuestion(Long uid, Long questionId, Long targetPlanId, String focusSubPoint) {
        QuestionBank q = questionBankRepo.findById(questionId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "题目已失效"));
        QuestionView resumed = resumeActiveOrPark(uid, targetPlanId, null, null);
        if (resumed != null) return resumed;

        DrillRun run = new DrillRun();
        run.setUserId(uid);
        run.setQuestionId(q.getId());
        run.setMode(DrillMode.LEARN);
        run.setStatus(DrillRunStatus.READY);
        run.setFocusSubPoint(focusSubPoint);
        try {
            run = runRepo.save(run);
        } catch (DataIntegrityViolationException e) {
            // 闸门二：部分唯一索引物理闸门，已有未闭环作答（并发兜底）
            throw new ResponseStatusException(CONFLICT, "已有未完成的作答，请先完成或搁置");
        }
        return new QuestionView(run.getId(), q.getId(), q.getStem(), q.getProbeType().name(), q.getResponseFormat().name());
    }

    /**
     * 提交作答并流式讲解（SSE 单端点，替代旧「submit 同步 JSON + 另开 tutor-stream」两段式）。
     *
     * <p>协议（text/event-stream）：
     * <ol>
     *   <li>{@code event: grade} → 判分结果（GradeView JSON），前端据此先渲染评分面板；</li>
     *   <li>{@code data: {"text":"..."}}（默认 message 事件）→ 逐 token 的讲解，前端累积显示；</li>
     *   <li>{@code event: done} → 完整讲解文本，前端用它兜底覆盖（修复偶发末尾截断）；</li>
     *   <li>{@code event: error} → 流式中途异常。</li>
     * </ol>
     *
     * <p>判分（answerService.submit）在写响应体之前同步完成——若抛闸门 409 等，由 Spring 错误
     * 机制直接返回，不会进 SSE 体；只有讲解流式阶段的中断才走 {@code event: error}。
     */
    @PostMapping(value = "/{runId}/submit", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> submit(
            @PathVariable Long runId, @RequestBody SubmitRequest req) {
        Long uid = currentUserId();
        GradeView grade = answerService.submit(uid, runId, req.rawAnswer(), req.timing(), req.activeSeconds());

        DrillTurn turn = turnRepo.findByRunIdAndRound(runId, 0)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "作答不存在"));
        if (turn.getRawAnswer() == null) {
            throw new ResponseStatusException(BAD_REQUEST, "本轮尚未作答");
        }
        String stem = turn.getStem();
        String pointsJson = turn.getPointsJson();
        String byConceptJson = turn.getByConceptJson();
        String rawAnswer = turn.getRawAnswer();
        final DrillTurn fTurn = turn;
        // 学习上下文（判分讲解依据：学生进度/概念要点/资料块/互联网补充）
        DrillRun submitRun = runRepo.findById(runId).orElse(null);
        final String context = submitRun == null ? null : contextOf(uid, submitRun.getQuestionId());

        StreamingResponseBody body = out -> {
            try {
                // 1) grade 事件：先让前端渲染评分面板（verdict），与讲解解耦
                out.write(("event: grade\ndata: " + objectMapper.writeValueAsString(grade) + "\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();

                // 2) 逐 token 推讲解
                final StringBuilder buf = new StringBuilder();
                String full = tutorGenerator.streamExplain(stem, pointsJson, byConceptJson, rawAnswer,
                        context,
                        token -> {
                            buf.append(token);
                            try {
                                out.write(("data: {\"text\":\"" + jsonEscape(token) + "\"}\n\n")
                                        .getBytes(StandardCharsets.UTF_8));
                                out.flush();
                            } catch (Exception e) {
                                log.debug("SSE token 推送异常（已吞）: {}", e.getMessage());
                            }
                        },
                        r -> sseReasoning(out, r));;

                // 完整文本写库（让对话线下次刷新也能拿到）
                if (full != null) {
                    fTurn.setTutorText(full);
                    turnRepo.save(fTurn);
                }

                String donePayload = full == null ? "" : jsonEscape(full);
                out.write(("event: done\ndata: {\"text\":\"" + donePayload + "\"}\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (Exception e) {
                log.warn("submit SSE 推送异常", e);
                try {
                    out.write(("event: error\ndata: " + jsonEscape(e.getMessage()) + "\n\n")
                            .getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (Exception ignored) {
                }
            }
        };

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(body);
    }

    // ---------------------------------------------------- 对话式作答（chat + finish）

    /**
     * 对话式作答 SSE（POST /{runId}/chat）：
     * <p>用户在聊天页发送消息后，AI 以辅导老师身份流式回复。<b>不判分</b>——评分延迟到
     * 用户点「结束并评分」时由 {@code POST /{runId}/finish} 一次性完成。
     *
     * <p>协议（text/event-stream）：
     * <ol>
     *   <li>{@code data: {"text":"..."}}（默认 message 事件）→ 逐 token 的 AI 回复；</li>
     *   <li>{@code event: done} → 回复结束；</li>
     *   <li>{@code event: error} → 流式中途异常。</li>
     * </ol>
     *
     * <p>同步段完成：验证 run 归属 + 状态、保存用户回答为 DrillTurn、首次对话时推进 run 到 ANSWERING。
     * 流式段调 {@link TutorGenerator#streamChat} 生成对话回复，完成后写回 turn.tutorText。
     *
     * <p>状态门槛放宽：除 READY/ANSWERING 外，也允许对「已判分的 LEARN run」继续对话——历史记录
     * 「继续对话」= 用户向 AI 提问（追问是用户问 AI），不重新评分，GRADED 状态保持不变。
     */
    @PostMapping(value = "/{runId}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> chat(
            @PathVariable Long runId, @RequestBody ChatRequest req) {
        Long uid = currentUserId();

        DrillRun run = runRepo.findByUserIdAndId(uid, runId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "作答不存在"));
        // 状态门槛：READY/ANSWERING 正常对话；另放行「已判分的 LEARN run」——历史记录「继续对话」
        // = 用户在同一道题上继续向 AI 提问（追问是用户问 AI），不重新评分、不改 GRADED 状态。
        boolean continueAfterGraded =
                run.getStatus() == DrillRunStatus.GRADED && run.getMode() == DrillMode.LEARN;
        if (run.getStatus() != DrillRunStatus.READY && run.getStatus() != DrillRunStatus.ANSWERING
                && !continueAfterGraded) {
            throw new ResponseStatusException(BAD_REQUEST, "当前作答状态不可对话: " + run.getStatus());
        }
        if (req.rawAnswer() == null || req.rawAnswer().isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "回答不能为空");
        }

        QuestionBank q = questionBankRepo.findById(run.getQuestionId())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "题目不存在"));

        // 保存用户回答为新 turn（round 递增）
        List<DrillTurn> existing = turnRepo.findByRunIdOrderByRoundAsc(runId);
        int nextRound = existing.isEmpty() ? 0 : existing.getLast().getRound() + 1;
        DrillTurn turn = new DrillTurn();
        turn.setRunId(runId);
        turn.setRound(nextRound);
        turn.setStem(q.getStem());
        turn.setRawAnswer(req.rawAnswer());
        turnRepo.save(turn);

        // 首次对话：run READY -> ANSWERING
        if (run.getStatus() == DrillRunStatus.READY) {
            run.setStatus(DrillRunStatus.ANSWERING);
            runRepo.save(run);
        }

        // —— 答案揭示边界（“得到答案之前”的评分依据）——
        // 只接受前端「看答案」按钮携带的 reveal=true。普通文本无论包含“怎么做”“如何实现”等词，
        // 都一律视为学生作答或提问，不能靠关键词猜测其意图，否则会误截断评分并泄露答案。
        // 自然语言索要答案时，辅导 AI 只会提示用户使用按钮，由用户显式确认后才揭示。
        boolean reveal = Boolean.TRUE.equals(req.reveal());
        boolean isPreGraded = run.getStatus() == DrillRunStatus.READY
                || run.getStatus() == DrillRunStatus.ANSWERING;
        if (reveal && isPreGraded && run.getAnswerRevealedRound() == null) {
            run.setAnswerRevealedRound(nextRound);
            runRepo.save(run);
        }

        // 收集全部 turns（含刚创建的）供 AI 参考对话历史
        List<DrillTurn> allTurns = turnRepo.findByRunIdOrderByRoundAsc(runId);
        // 追问安全阀（仅判分前）：学生已作答的轮数。
        // 判分后（继续对话 = 用户向 AI 提问）传 -1，走通用苏格拉底引导、不限小问。
        // reveal 时该值不影响：reveal 模式优先，直接给完整讲解、不再追问。
        // 正常流程由 AI 按「小问 ≤ 4 个、确认理解后才出下一问」自行控制，这里只是兜底。
        int followupIndex = isPreGraded
                ? (int) allTurns.stream().filter(t -> t.getRawAnswer() != null
                        && !t.getRawAnswer().isBlank()).count()
                : -1;
        String stem = q.getStem();
        String pointsJson = q.getPointsJson();
        // 出题时预生成的「追问小问」清单：老师按顺序逐条问，问完就停（学生提前掌握可提前收）。
        final List<String> followups = extractFollowups(pointsJson);
        final DrillTurn fTurn = turn;
        final boolean fReveal = reveal;
        final String context = contextOf(uid, run.getQuestionId());

        StreamingResponseBody body = out -> {
            try {
                // 揭示边界触发：先推 event:reveal，前端在聊天线程里渲染“参考答案”分隔线
                if (fReveal && isPreGraded) {
                    out.write("event: reveal\ndata: {}\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                String full = tutorGenerator.streamChat(stem, pointsJson, followups, allTurns, context,
                        token -> {
                            try {
                                out.write(("data: {\"text\":\"" + jsonEscape(token) + "\"}\n\n")
                                        .getBytes(StandardCharsets.UTF_8));
                                out.flush();
                            } catch (Exception e) {
                                log.debug("chat SSE token 推送异常（已吞）: {}", e.getMessage());
                            }
                        },
                        r -> sseReasoning(out, r),
                        fReveal, followupIndex, TutorGenerator.SAFETY_ANSWER_CAP);

                // 完整文本只写回 turn。AI 可以停止追问并提示用户点击按钮，但不得替用户结束或触发评分。
                if (full != null) {
                    fTurn.setTutorText(full.trim());
                    turnRepo.save(fTurn);
                }

                out.write("event: done\ndata: {}\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (Exception e) {
                log.warn("chat SSE 推送异常", e);
                try {
                    out.write(("event: error\ndata: " + jsonEscape(e.getMessage()) + "\n\n")
                            .getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (Exception ignored) {
                }
            }
        };

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(body);
    }

    /**
     * 结束对话并评分（POST /{runId}/finish）：
     * <p>基于整轮对话（所有 /chat 的用户回答）一次性判分，返回 GradeView。
     * 判分后 run 状态置 GRADED，mastery 按概念粒度更新。
     */
    @PostMapping("/{runId}/finish")
    public GradeView finish(@PathVariable Long runId) {
        Long uid = currentUserId();
        return answerService.finish(uid, runId);
    }

    // -------------------------------------------------------- REHEARSAL

    @PostMapping("/rehearsal/start")
    public RehearsalView rehearsalStart(@RequestBody(required = false) RehearsalStartRequest req) {
        Long uid = currentUserId();
        return rehearsalService.start(uid, req == null ? null : req.conceptId());
    }
    /**
     * 模拟面试作答并流式讲解（SSE 单端点，与 LEARN {@code submit} 同构）。
     *
     * <p>协议（text/event-stream）：
     * <ol>
     *   <li>{@code event: result} → 判分/下一轮结果（RehearsalView JSON），前端据此渲染下一问或结算；</li>
     *   <li>{@code data: {"text":"..."}}（默认 message 事件）→ 逐 token 的讲解，前端累积显示；</li>
     *   <li>{@code event: done} → 讲解结束（不再回带完整文本，避免末尾整段重复）；</li>
     *   <li>{@code event: error} → 流式中途异常。</li>
     * </ol>
     *
     * <p>判分（rehearsalService.answer）在写响应体之前同步完成——若抛闸门 409 等，由 Spring 错误
     * 机制直接返回，不会进 SSE 体；仅讲解流式阶段的中断才走 {@code event: error}。
     * 讲解针对"刚作答的那一轮"（view.round），写成 JSON 后再逐 token 推，避免旧两段式里
     * answer 同步卡慢 + 前端再单独开 tutor-stream 拉讲解的双重 LLM 往返。
     */

    @PostMapping(value = "/rehearsal/{runId}/answer", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> rehearsalAnswer(
            @PathVariable Long runId, @RequestBody RehearsalAnswerRequest req) {
        // 同步段取 uid（SecurityContext 不跨 async 线程）
        Long uid = currentUserId();
        // 同步判分（决定 advance / settle），与 LEARN submit 同策略
        RehearsalView view = rehearsalService.answer(uid, runId, req.rawAnswer());
        // 取「刚作答那轮」的 turn 生成讲解（view.round 即已作答轮）
        DrillTurn turn = turnRepo.findByRunIdAndRound(runId, view.round())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "本轮问题不存在"));
        if (turn.getRawAnswer() == null) {
            throw new ResponseStatusException(BAD_REQUEST, "本轮尚未作答");
        }
        String stem = turn.getStem();
        String pointsJson = turn.getPointsJson();
        String byConceptJson = turn.getByConceptJson();
        String rawAnswer = turn.getRawAnswer();
        final DrillTurn fTurn = turn;
        final RehearsalView fView = view;
        DrillRun reheRun = runRepo.findById(runId).orElse(null);
        final String context = reheRun == null ? null : contextOf(uid, reheRun.getQuestionId());

        StreamingResponseBody body = out -> {
            try {
                // 1) result 事件：先让前端渲染下一问 / 结算卡
                out.write(("event: result\ndata: " + objectMapper.writeValueAsString(fView) + "\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();

                // 2) 逐 token 推讲解
                final StringBuilder buf = new StringBuilder();
                String full = tutorGenerator.streamExplain(stem, pointsJson, byConceptJson, rawAnswer,
                        context,
                        token -> {
                            buf.append(token);
                            try {
                                out.write(("data: {\"text\":\"" + jsonEscape(token) + "\"}\n\n")
                                        .getBytes(StandardCharsets.UTF_8));
                                out.flush();
                            } catch (Exception e) {
                                log.debug("SSE token 推送异常（已吞）: {}", e.getMessage());
                            }
                        },
                        r -> sseReasoning(out, r));;

                // 完整文本写库（让对话线下次刷新也能拿到）
                if (full != null) {
                    fTurn.setTutorText(full);
                    turnRepo.save(fTurn);
                }

                // done 带回完整文本：前端用它兜底覆盖本地累积，修复偶发末尾截断。
                // 注意：这是安全网而非"重复消息"——前端 onDone 是替换累积文本，UI 不会重复出现气泡；
                // 之前 raw curl 看到的整段只是 SSE 原始帧，属正常兜底机制。
                String donePayload = full == null ? "" : jsonEscape(full);
                out.write(("event: done\ndata: {\"text\":\"" + donePayload + "\"}\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (Exception e) {
                log.warn("rehearsal answer SSE 推送异常", e);
                try {
                    out.write(("event: error\ndata: " + jsonEscape(e.getMessage()) + "\n\n")
                            .getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (Exception ignored) {
                }
            }
        };

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(body);
    }

    /**
     * LEARN grade 卡"继续追问"按钮：基于已 GRADED 的 LEARN run spawn 一条 mode=REHEARSAL 的追问场，
     * 复用 questionId，maxRound=10 让用户追问到底，settle 跳过 mastery（不算正式面试，不取 L3）。
     * 返回追问场第一轮的 RehearsalView。
     */
    @PostMapping("/{runId}/followup")
    public RehearsalView followup(@PathVariable Long runId) {
        Long uid = currentUserId();
        return rehearsalService.spawnFollowup(uid, runId);
    }

    /** 追问/模拟面试主动结束：用户点"下一题（结束追问）"或"结算本场"时调用 */
    @PostMapping("/rehearsal/{runId}/end")
    public RehearsalView rehearsalEnd(@PathVariable Long runId) {
        Long uid = currentUserId();
        return rehearsalService.endRehearsal(uid, runId);
    }

    // ------------------------------------------------------------ 教学讲解 SSE 流

    /**
     * 教学讲解 SSE 流：前端 EventSource 订阅此 URL，逐 token 累积显示讲解。
     * 服务端调 TutorGenerator 流式生成，每 token 写 {@code data: <token>\n\n}，
     * 写完时再补 {@code event: done} 与完整文本，最后把讲解写回 drill_turn.tutor_text。
     *
     * <p>路由注意：本端点路径是 {@code /{runId}/tutor-stream}，单段动态段；
     * 不能与 {@code GET /{runId}} 撞路由（SSE 端点在前缀排序靠后），Spring MVC
     * 按"具体路径优先于路径变量"，先匹配本端点的子串。
     */
    @GetMapping(value = "/{runId}/tutor-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> tutorStream(
            @PathVariable Long runId,
            @RequestParam(defaultValue = "0") int round) {
        // 同步段取 uid（SecurityContext 不跨 async 线程，uid 必须现在拿到）
        Long uid = currentUserId();
        DrillRun run = runRepo.findByUserIdAndId(uid, runId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "作答不存在"));
        // 主问 LEARN 讲解要求 run 已判分(GRADED)；模拟面试 REHEARSAL 多轮间 run 处于 ANSWERING
        // 态（不立即 GRADED），每轮用户作答后即当轮生成讲解，故对 REHEARSAL 模式放宽门槛。
        if (run.getStatus() != DrillRunStatus.GRADED && run.getMode() != DrillMode.REHEARSAL) {
            throw new ResponseStatusException(BAD_REQUEST, "该作答尚未判分，无讲解");
        }
        DrillTurn turn = turnRepo.findByRunIdAndRound(runId, round)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "本轮问题不存在"));
        if (turn.getRawAnswer() == null) {
            throw new ResponseStatusException(BAD_REQUEST, "本轮尚未作答");
        }

        // 捕获到 lambda 里的变量需为 final
        String stem = turn.getStem();
        String pointsJson = turn.getPointsJson();
        String byConceptJson = turn.getByConceptJson();
        String rawAnswer = turn.getRawAnswer();
        final DrillTurn fTurn = turn;       // mutable turn 在 lambda 内被 setTutorText 写库
        final String context = contextOf(uid, run.getQuestionId());

        StreamingResponseBody body = out -> {
            try {
                out.write("event: start\ndata: {}\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();

                final StringBuilder buf = new StringBuilder();
                String full = tutorGenerator.streamExplain(stem, pointsJson, byConceptJson, rawAnswer,
                        context,
                        token -> {
                            buf.append(token);
                            try {
                                // token 帧统一包成 JSON：前端 JSON.parse 取 .text，天然处理转义
                                out.write(("data: {\"text\":\"" + jsonEscape(token) + "\"}\n\n")
                                        .getBytes(StandardCharsets.UTF_8));
                                out.flush();
                            } catch (Exception e) {
                                log.debug("SSE token 推送异常（已吞）: {}", e.getMessage());
                            }
                        },
                        r -> sseReasoning(out, r));;

                // 完整文本写库（让对话线下次刷新也能拿到）
                if (full != null) {
                    fTurn.setTutorText(full);
                    turnRepo.save(fTurn);
                }

                // done 不再回带完整文本：前端以逐 token 累积为准，避免末尾整段重复
                out.write("event: done\ndata: {}\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (Exception e) {
                log.warn("tutor-stream 推送异常", e);
                try {
                    out.write(("event: error\ndata: " + jsonEscape(e.getMessage()) + "\n\n")
                            .getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (Exception ignored) {
                }
            }
        };

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(body);
    }

    /**
     * SSE 行内 JSON 字符串转义：不仅转义引号/反斜杠/换行/回车，还转义全部 C0 控制字符
     * （含制表符 \t，Go 代码缩进常用）与 U+2028/U+2029，保证 {@code {"text":"..."}} 帧是合法 JSON。
     * 否则前端 JSON.parse 会失败、把原始帧当文本注入气泡，表现为代码块里出现 {"text":"..."} 乱码。
     */
    private static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.toString();
    }

    /** 从题目 points JSON 里取出出题时预生成的「追问小问」清单（空/异常则空列表）。 */
    private List<String> extractFollowups(String pointsJson) {
        try {
            JsonNode root = objectMapper.readTree(pointsJson);
            JsonNode fu = root == null ? null : root.get("followups");
            if (fu == null || !fu.isArray()) return List.of();
            List<String> out = new java.util.ArrayList<>();
            for (JsonNode n : fu) {
                String s = n.asText();
                if (s != null && !s.isBlank()) out.add(s);
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    // ------------------------------------------------------------- 内化

    @PostMapping("/{runId}/note")
    public NoteView note(@PathVariable Long runId, @RequestBody NoteRequest req) {
        Long uid = currentUserId();
        return noteService.write(uid, runId, req);
    }

    @GetMapping("/debt")
    public List<DebtView> debt() {
        return noteService.debt(currentUserId());
    }

    /** AI 复盘：题目 + 对话总结（欠缺） + 解题思路 + 记忆口诀（按 runId 缓存，只生成一次）。 */
    @GetMapping("/{runId}/review")
    public ReviewView review(@PathVariable Long runId) {
        return reviewService.review(currentUserId(), runId);
    }

    // ------------------------------------------------------------- 看数

    @GetMapping("/profile")
    public List<ProfileService.TopicProfile> profile() {
        Long uid = currentUserId();
        return profileService.profile(uid);
    }

    // -------------------------------------------------------- 问答记录

    @GetMapping("/history")
    public List<RunSummaryView> history() {
        return historyService.list(currentUserId());
    }

    /** 对话线：一道题（questionId）的完整问答历史，前端点卡片后渲染 */
    @GetMapping("/history/conversation/{questionId}")
    public ConversationView conversation(@PathVariable Long questionId) {
        return historyService.conversation(currentUserId(), questionId);
    }

    /**
     * 删除一道题的整条问答记录（级联：追问场 / 判分 / 复盘 / 笔记）。
     * 只删记录，不动掌握度与题库。删除前由前端二次确认。
     */
    @DeleteMapping("/history/conversation/{questionId}")
    public Map<String, Object> deleteConversation(@PathVariable Long questionId) {
        Long uid = currentUserId();
        int deleted = recordCleanupService.deleteConversation(uid, questionId);
        return Map.of("ok", true, "deleted", deleted);
    }

    /**
     * 删除单条作答记录及其全部关联数据（追问场 / 判分 / 复盘 / 笔记）。
     * 供「内化复盘」页删除欠账 / 复盘数据。删除前由前端二次确认。
     */
    @DeleteMapping("/runs/{runId}")
    public Map<String, Object> deleteRun(@PathVariable Long runId) {
        Long uid = currentUserId();
        int deleted = recordCleanupService.deleteRun(uid, runId);
        return Map.of("ok", true, "deleted", deleted);
    }

    @GetMapping("/{runId}")
    public RunDetailView historyDetail(@PathVariable Long runId) {
        return historyService.detail(currentUserId(), runId);
    }

    /** 概念所属学习方向（用于闸门的方向隔离）。 */
    private Long planIdOfConcept(Long conceptId) {
        return conceptRepo.findById(conceptId).map(Concept::getStudyPlanId).orElse(null);
    }

    /** 题目主概念所属学习方向（用于闸门的方向隔离）。 */
    private Long planIdOfQuestion(Long questionId) {
        Long primaryId = primaryConceptId(questionId);
        return primaryId == null ? null : planIdOfConcept(primaryId);
    }

    /**
     * 活跃 run 的主概念是否属于用户显式指定的层级。
     * 未指定层级（null）一律视为匹配；题目拿不到主概念时保守不匹配（宁可按目标层重出，也不顶掉请求）。
     */
    private boolean layerMatches(DrillRun run, Integer targetLayer) {
        if (targetLayer == null) return true;
        return conceptRepo.findById(primaryConceptId(run.getQuestionId()))
                .map(c -> c.getLayer() == targetLayer)
                .orElse(false);
    }

    /** 活跃 run 的主概念是否就是用户显式点开的概念（未指定概念则一律匹配）。 */
    private boolean conceptMatches(DrillRun run, Long targetConceptId) {
        if (targetConceptId == null) return true;
        Long primaryId = primaryConceptId(run.getQuestionId());
        return primaryId != null && primaryId.equals(targetConceptId);
    }

    private Long primaryConceptId(Long questionId) {
        QuestionBank q = questionBankRepo.findById(questionId).orElse(null);
        if (q == null || q.getConceptIds() == null || q.getConceptIds().length == 0) return null;
        return q.getConceptIds()[0].longValue();
    }

    /** 题目涉及的学习上下文（学生进度 + 概念要点 + 资料块 + 互联网补充），查不到返回 null。 */
    private String contextOf(Long uid, Long questionId) {
        QuestionBank q = questionBankRepo.findById(questionId).orElse(null);
        if (q == null || q.getConceptIds() == null || q.getConceptIds().length == 0) return null;
        java.util.List<Long> ids = java.util.Arrays.stream(q.getConceptIds())
                .map(Integer::longValue).toList();
        return progressContext.contextFor(uid, ids);
    }

    private boolean questionContainsConcept(Long questionId, Long conceptId) {
        QuestionBank q = questionBankRepo.findById(questionId).orElse(null);
        if (q == null || q.getConceptIds() == null) return false;
        return java.util.Arrays.stream(q.getConceptIds())
                .anyMatch(id -> id != null && id.longValue() == conceptId.longValue());
    }

    /** 把一段模型思考（reasoning_content）推给前端（event: reasoning），供"思考过程"面板展示。 */
    private void sseReasoning(java.io.OutputStream out, String reasoning) {
        try {
            out.write(("event: reasoning\ndata: {\"text\":\"" + jsonEscape(reasoning) + "\"}\n\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception ignored) {
        }
    }

    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Long)) {
            throw new ResponseStatusException(UNAUTHORIZED, "未鉴权");
        }
        return (Long) auth.getPrincipal();
    }

    public record StartRequest(Long conceptId, String subPoint) {}

    public record OutlineView(Long conceptId, String name, String topic, List<String> subPoints,
                              List<String> completedSubPoints, boolean cached) {}

    public record StartPlanRequest(Long planId, String mode, Integer layer, Long conceptId) {}


    public record RestartRequest(Long runId) {}
}
