package interview.homegrown.modules.drill.web;

import interview.homegrown.modules.drill.domain.DrillMode;
import interview.homegrown.modules.drill.domain.DrillRun;
import interview.homegrown.modules.drill.domain.DrillRunStatus;
import interview.homegrown.modules.drill.domain.QuestionBank;
import interview.homegrown.modules.drill.domain.SelectedTask;
import interview.homegrown.modules.drill.repository.DrillRunRepository;
import interview.homegrown.modules.drill.repository.QuestionBankRepository;
import interview.homegrown.modules.drill.service.AnswerService;
import interview.homegrown.modules.drill.service.CorpusService;
import interview.homegrown.modules.drill.service.HistoryService;
import interview.homegrown.modules.drill.service.NoteService;
import interview.homegrown.modules.drill.service.ProfileService;
import interview.homegrown.modules.drill.service.QuestionService;
import interview.homegrown.modules.drill.service.RehearsalService;
import interview.homegrown.modules.drill.service.SelectionService;
import interview.homegrown.modules.drill.web.dto.DebtView;
import interview.homegrown.modules.drill.web.dto.GradeView;
import interview.homegrown.modules.drill.web.dto.NoteRequest;
import interview.homegrown.modules.drill.web.dto.NoteView;
import interview.homegrown.modules.drill.web.dto.QuestionView;
import interview.homegrown.modules.drill.web.dto.RehearsalAnswerRequest;
import interview.homegrown.modules.drill.web.dto.RehearsalStartRequest;
import interview.homegrown.modules.drill.web.dto.RehearsalView;
import interview.homegrown.modules.drill.web.dto.RunDetailView;
import interview.homegrown.modules.drill.web.dto.RunSummaryView;
import interview.homegrown.modules.drill.web.dto.SubmitRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

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

    private final SelectionService selectionService;
    private final QuestionService questionService;
    private final AnswerService answerService;
    private final ProfileService profileService;
    private final RehearsalService rehearsalService;
    private final NoteService noteService;
    private final HistoryService historyService;
    private final CorpusService corpusService;
    private final DrillRunRepository runRepo;
    private final QuestionBankRepository questionBankRepo;

    public DrillController(SelectionService selectionService, QuestionService questionService,
                           AnswerService answerService, ProfileService profileService,
                           RehearsalService rehearsalService, NoteService noteService,
                           HistoryService historyService, CorpusService corpusService,
                           DrillRunRepository runRepo, QuestionBankRepository questionBankRepo) {
        this.selectionService = selectionService;
        this.questionService = questionService;
        this.answerService = answerService;
        this.profileService = profileService;
        this.rehearsalService = rehearsalService;
        this.noteService = noteService;
        this.historyService = historyService;
        this.corpusService = corpusService;
        this.runRepo = runRepo;
        this.questionBankRepo = questionBankRepo;
    }

    // ------------------------------------------------------------ LEARN

    @PostMapping("/next")
    public QuestionView next() {
        Long uid = currentUserId();
        // 债务闸门已按用户决策移除：欠账不拦截开新题（欠账清单仍经 GET /debt 暴露，供复盘页自选消化）
        var task = selectionService.pickNext(uid);         // 确定性选题（服务端）
        return openRun(uid, task);
    }

    /** 用户自选概念开练（痛点1：用户掌 what，服务端仍用 pickFor 定 task）。 */
    @PostMapping("/start")
    public QuestionView start(@RequestBody StartRequest req) {
        Long uid = currentUserId();
        // 债务闸门已移除（同上）
        var task = selectionService.pickFor(uid, req.conceptId());
        return openRun(uid, task);
    }

    /** 方向级入口：继续（plan 内确定性选题）/ 复习（plan 内到期已掌握项）。 */
    @PostMapping("/start-plan")
    public QuestionView startPlan(@RequestBody StartPlanRequest req) {
        Long uid = currentUserId();
        // 债务闸门已移除（同上）
        String mode = req.mode() == null ? "continue" : req.mode();
        SelectedTask task = switch (mode) {
            case "review" -> selectionService.pickReviewWithinPlan(uid, req.planId());
            default -> selectionService.pickNextWithinPlan(uid, req.planId());
        };
        return openRun(uid, task);
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

        // 已有未闭环作答 -> 恢复之（物理闸门语义：同一时间只做一道）
        List<DrillRun> activeRuns = runRepo.findByUserIdAndStatusIn(uid, ACTIVE_STATUSES);
        if (!activeRuns.isEmpty()) {
            DrillRun run = activeRuns.getFirst();
            QuestionBank aq = questionBankRepo.findById(run.getQuestionId())
                    .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "题目已失效"));
            return new QuestionView(run.getId(), aq.getStem(), aq.getProbeType().name(), aq.getResponseFormat().name());
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
        return new QuestionView(run.getId(), q.getStem(), q.getProbeType().name(), q.getResponseFormat().name());
    }

    private static final List<DrillRunStatus> ACTIVE_STATUSES = List.of(DrillRunStatus.READY, DrillRunStatus.ANSWERING);

    private QuestionView openRun(Long uid, SelectedTask task) {
        // 若已有未闭环作答，直接恢复该题：避免物理闸门冲突，也避免重复调用 LLM 出题。
        List<DrillRun> activeRuns = runRepo.findByUserIdAndStatusIn(uid, ACTIVE_STATUSES);
        if (!activeRuns.isEmpty()) {
            DrillRun run = activeRuns.getFirst();
            QuestionBank q = questionBankRepo.findById(run.getQuestionId())
                    .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "题目已失效"));
            return new QuestionView(run.getId(), q.getStem(), q.getProbeType().name(), q.getResponseFormat().name());
        }

        // 资料注入：若该概念所属方向绑了用户的书/项目资料，把解析文本喂给出题器（v1 全文注入）
        String ref = corpusService.referenceForConcept(task.conceptId());
        var q = questionService.generate(task, ref);       // 出题（LLM 填空）
        DrillRun run = new DrillRun();
        run.setUserId(uid);
        run.setQuestionId(q.getId());
        run.setMode(DrillMode.LEARN);
        run.setStatus(DrillRunStatus.READY);
        try {
            run = runRepo.save(run);
        } catch (DataIntegrityViolationException e) {
            // 闸门二：部分唯一索引物理闸门，已有未闭环作答（并发兜底）
            throw new ResponseStatusException(CONFLICT, "已有未完成的作答，请先完成或搁置");
        }
        return new QuestionView(run.getId(), q.getStem(), q.getProbeType().name(), q.getResponseFormat().name());
    }

    @PostMapping("/{runId}/submit")
    public GradeView submit(@PathVariable Long runId, @RequestBody SubmitRequest req) {
        Long uid = currentUserId();
        return answerService.submit(uid, runId, req.rawAnswer(), req.timing(), req.activeSeconds());
    }

    // -------------------------------------------------------- REHEARSAL

    @PostMapping("/rehearsal/start")
    public RehearsalView rehearsalStart(@RequestBody(required = false) RehearsalStartRequest req) {
        Long uid = currentUserId();
        return rehearsalService.start(uid, req == null ? null : req.conceptId());
    }

    /** 同一个端点既可能返回"下一个追问"，也可能返回本场结算，靠 finished 区分 */
    @PostMapping("/rehearsal/{runId}/answer")
    public RehearsalView rehearsalAnswer(@PathVariable Long runId,
                                         @RequestBody RehearsalAnswerRequest req) {
        Long uid = currentUserId();
        return rehearsalService.answer(uid, runId, req.rawAnswer());
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

    @GetMapping("/{runId}")
    public RunDetailView historyDetail(@PathVariable Long runId) {
        return historyService.detail(currentUserId(), runId);
    }

    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Long)) {
            throw new ResponseStatusException(UNAUTHORIZED, "未鉴权");
        }
        return (Long) auth.getPrincipal();
    }

    public record StartRequest(Long conceptId) {}

    public record StartPlanRequest(Long planId, String mode) {}

    public record RestartRequest(Long runId) {}
}
