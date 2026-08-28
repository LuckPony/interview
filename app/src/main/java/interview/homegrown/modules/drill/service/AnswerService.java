package interview.homegrown.modules.drill.service;

import interview.homegrown.modules.drill.domain.DrillPhase;
import interview.homegrown.modules.drill.domain.DrillRun;
import interview.homegrown.modules.drill.domain.DrillRunStatus;
import interview.homegrown.modules.drill.domain.GradeResult;
import interview.homegrown.modules.drill.domain.Grade;
import interview.homegrown.modules.drill.repository.DrillRunRepository;
import interview.homegrown.modules.drill.repository.GradeResultRepository;
import interview.homegrown.modules.drill.web.dto.GradeView;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * 作答服务：把 run 推进到 ANSWERING，落 opt-in 计时与有效时长，再委托判分。
 *
 * <p>注意状态机的<b>后半段不在这里</b>：READY/ANSWERING -&gt; GRADED 由
 * {@link GradingService#grade} 在判分事务内完成。这样切是有意的 —— 只有判分成功落库了
 * 才算闭环，如果在这里提前置 GRADED，判分一旦失败，物理闸门就会放行下一题，
 * 用户会留下一条永远没有成绩的作答。
 *
 * <p>已知待收敛项：GRADED 目前有三处写入点（本流程的 GradingService、REHEARSAL 的
 * RehearsalService.settle、以及将来的 72h 自动 PARKED 任务）。状态迁移分散在多个 service
 * 是隐患，后续应收敛成一个 DrillRunStateMachine。
 */
@Service
public class AnswerService {

    private final DrillRunRepository runRepo;
    private final GradingService gradingService;
    private final GradeResultRepository gradeRepo;

    public AnswerService(DrillRunRepository runRepo, GradingService gradingService,
                         GradeResultRepository gradeRepo) {
        this.runRepo = runRepo;
        this.gradingService = gradingService;
        this.gradeRepo = gradeRepo;
    }

    public GradeView submit(Long userId, Long runId, String rawAnswer, String timing, Integer activeSeconds) {
        DrillRun run = runRepo.findByUserIdAndId(userId, runId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "作答不存在"));
        if (run.getStatus() != DrillRunStatus.READY && run.getStatus() != DrillRunStatus.ANSWERING) {
            throw new ResponseStatusException(BAD_REQUEST, "当前作答状态不可提交: " + run.getStatus());
        }

        run.setStatus(DrillRunStatus.ANSWERING);   // 进入作答
        if (timing != null) run.setTiming(timing);
        if (activeSeconds != null) run.setActiveSeconds(activeSeconds);
        runRepo.save(run);

        return gradingService.grade(userId, runId, rawAnswer);
    }

    /** 延迟评分：基于整轮对话一次性判分，委托 GradingService.finish。 */
    public GradeView finish(Long userId, Long runId) {
        return gradingService.finish(userId, runId);
    }

    /**
     * 放弃本次作答（三阶段练习中「看答案后不再作答」的收尾）：
     * 未独立作答即看答案 → 按 AGAIN（未通过）结算，run 置 GRADED 闭环，
     * 物理闸门随即放行下一题。不写掌握度降级之外的任何教学状态。
     */
    public GradeView abandon(Long userId, Long runId) {
        DrillRun run = runRepo.findByUserIdAndId(userId, runId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "作答不存在"));
        if (run.getStatus() != DrillRunStatus.READY && run.getStatus() != DrillRunStatus.ANSWERING) {
            throw new ResponseStatusException(BAD_REQUEST, "当前作答状态不可放弃: " + run.getStatus());
        }
        run.setStatus(DrillRunStatus.GRADED);
        run.setPreGrade(Grade.AGAIN.name());
        run.setFinalGrade(Grade.AGAIN.name());
        run.setSocraticState(DrillPhase.DONE);
        runRepo.save(run);

        GradeResult gr = new GradeResult();
        gr.setRunId(runId);
        gr.setQuestionId(run.getQuestionId());
        gr.setAnswerHash("abandon");
        gr.setByConceptJson("[]");
        gr.setRawScore(BigDecimal.ZERO);
        gr.setGrade(Grade.AGAIN);
        gradeRepo.save(gr);

        return new GradeView(runId, run.getQuestionId(), 0, Grade.AGAIN.name(), "[]");
    }
}
