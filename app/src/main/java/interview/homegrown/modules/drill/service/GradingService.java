package interview.homegrown.modules.drill.service;

import interview.homegrown.modules.drill.domain.ConceptRole;
import interview.homegrown.modules.drill.domain.DrillMode;
import interview.homegrown.modules.drill.domain.DrillRun;
import interview.homegrown.modules.drill.domain.DrillRunStatus;
import interview.homegrown.modules.drill.domain.DrillTurn;
import interview.homegrown.modules.drill.domain.Grade;
import interview.homegrown.modules.drill.domain.GradeResult;
import interview.homegrown.modules.drill.domain.Mastery;
import interview.homegrown.modules.drill.domain.QuestionBank;
import interview.homegrown.modules.drill.grader.ConceptScore;
import interview.homegrown.modules.drill.grader.Grader;
import interview.homegrown.modules.drill.grader.GraderMcq;
import interview.homegrown.modules.drill.grader.GraderText;
import interview.homegrown.modules.drill.repository.DrillRunRepository;
import interview.homegrown.modules.drill.repository.DrillTurnRepository;
import interview.homegrown.modules.drill.repository.GradeResultRepository;
import interview.homegrown.modules.drill.repository.MasteryRepository;
import interview.homegrown.modules.drill.repository.QuestionBankRepository;
import interview.homegrown.modules.drill.web.dto.GradeView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.NOT_IMPLEMENTED;

/**
 * 判分编排：按 response_format 分派 Grader，分数与档位全由服务端算。
 * 之后按<b>概念粒度</b>更新掌握度与排程，并推进 run 状态到 GRADED。
 *
 * <p>组合题的角色规则（修订 8）：
 * <ul>
 *   <li>PRIMARY：正常升降，上限由 mode 决定（LEARN 封顶 L2，REHEARSAL 才发 L3）。</li>
 *   <li>ANCHOR：<b>封顶 GOOD、上限 L2，但不封底</b>。理由是锚点只是被顺带考到，答好了不该
 *       靠蹭分虚高；但答错说明是真忘了，必须照常掉级并重新排进复习。</li>
 * </ul>
 */
@Service
public class GradingService {

    private static final int ANCHOR_LEVEL_CAP = 2;

    private final GradeResultRepository gradeRepo;
    private final DrillRunRepository runRepo;
    private final QuestionBankRepository qbRepo;
    private final MasteryRepository masteryRepo;
    private final DrillTurnRepository turnRepo;
    private final GraderText graderText;
    private final GraderMcq graderMcq;
    private final ScheduleService scheduleService;

    public GradingService(GradeResultRepository gradeRepo, DrillRunRepository runRepo,
                          QuestionBankRepository qbRepo, MasteryRepository masteryRepo,
                          DrillTurnRepository turnRepo,
                          GraderText graderText, GraderMcq graderMcq, ScheduleService scheduleService) {
        this.gradeRepo = gradeRepo;
        this.runRepo = runRepo;
        this.qbRepo = qbRepo;
        this.masteryRepo = masteryRepo;
        this.turnRepo = turnRepo;
        this.graderText = graderText;
        this.graderMcq = graderMcq;
        this.scheduleService = scheduleService;
    }

    @Transactional
    public GradeView grade(Long userId, Long runId, String rawAnswer) {
        DrillRun run = runRepo.findByUserIdAndId(userId, runId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "作答不存在"));
        if (run.getStatus() != DrillRunStatus.READY && run.getStatus() != DrillRunStatus.ANSWERING) {
            throw new ResponseStatusException(BAD_REQUEST, "当前作答状态不可提交: " + run.getStatus());
        }
        QuestionBank q = qbRepo.findById(run.getQuestionId())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "题目不存在"));

        boolean timed = run.getTiming() != null && !run.getTiming().equals("NONE");

        Grader grader = switch (q.getResponseFormat()) {
            case FREE_TEXT, STRUCTURED -> graderText;
            case CHOICE -> graderMcq;
            case CODE -> throw new ResponseStatusException(NOT_IMPLEMENTED, "CODE 待接力扣判题");
        };
        Grader.GraderOutput out = grader.grade(runId, q, rawAnswer, timed);

        GradeResult gr = new GradeResult();
        gr.setRunId(runId);
        gr.setQuestionId(q.getId());
        gr.setAnswerHash(Integer.toHexString(rawAnswer.hashCode()));
        gr.setByConceptJson(out.byConceptJson());
        gr.setRawScore(out.rawScore());
        gr.setGrade(out.grade());
        gradeRepo.save(gr);

        // LEARN 模式把题干、评分点、用户原答案、判分结果落到 drill_turn，
        // 供「问答记录」回看自己的作答；REHEARSAL 已在 RehearsalService 自行维护 turns。
        if (run.getMode() == DrillMode.LEARN) {
            DrillTurn turn = new DrillTurn();
            turn.setRunId(runId);
            turn.setRound(0);
            turn.setStem(q.getStem());
            turn.setPointsJson(q.getPointsJson());
            turn.setRawAnswer(rawAnswer);
            turn.setByConceptJson(out.byConceptJson());
            turn.setRawScore(out.rawScore());
            turnRepo.save(turn);
        }

        applyMastery(userId, out.conceptScores(), run.getMode(), timed);

        run.setStatus(DrillRunStatus.GRADED);
        runRepo.save(run);

        return new GradeView(runId, q.getId(),
                out.rawScore() == null ? 0 : out.rawScore().doubleValue(),
                out.grade().name(), out.byConceptJson());
    }

    /** per concept 更新掌握度与下次复习时间 */
    public void applyMastery(Long userId, List<ConceptScore> scores, DrillMode mode, boolean timed) {
        for (ConceptScore cs : scores) {
            Grade effective = effectiveGrade(cs);
            int level = computeLevel(userId, cs.conceptId(), effective, cs.role(), mode);
            Instant due = scheduleService.nextDue(effective, timed);
            masteryRepo.upsert(userId, cs.conceptId(), level, effective.name(), due);
        }
    }

    /** ANCHOR 封顶 GOOD：不给 EASY，避免"顺带答对"把复习间隔拉得太长 */
    private Grade effectiveGrade(ConceptScore cs) {
        if (cs.role() == ConceptRole.ANCHOR && cs.grade() == Grade.EASY) {
            return Grade.GOOD;
        }
        return cs.grade();
    }

    private int computeLevel(Long userId, Long conceptId, Grade grade, ConceptRole role, DrillMode mode) {
        Mastery m = masteryRepo.findByUserIdAndConceptId(userId, conceptId).orElse(null);
        int cur = m == null ? 0 : m.getMasteryLevel();
        int cap = role == ConceptRole.ANCHOR
                ? ANCHOR_LEVEL_CAP                                   // 锚点不能靠蹭题升到 L3
                : (mode == DrillMode.REHEARSAL ? 3 : 2);             // L3 只在模拟面试里发
        return switch (grade) {
            case AGAIN -> Math.max(0, cur - 1);     // 不封底：锚点答错照样掉
            case HARD -> cur;
            case GOOD, EASY -> Math.min(cap, cur + 1);
        };
    }
}
