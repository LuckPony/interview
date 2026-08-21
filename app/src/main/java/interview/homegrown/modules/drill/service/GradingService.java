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
import interview.homegrown.modules.drill.domain.ResponseFormat;
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
        // 教学讲解（tutor_text）由 SSE 端点 /drill/{runId}/tutor-stream 异步生成后写回，
        // 这里不再同步写——避免阻塞判分提交，且让前端能逐 token 流式接收。
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

    /**
     * 延迟评分：基于整轮对话（多轮 chat 的全部用户消息）一次性判分。
     * <p>
     * 与 {@link #grade} 的区别：turns 已由 /chat 端点逐轮创建，这里不新建 turn，
     * 而是把所有用户回答拼接成 combined answer 交给 grader，然后把判分结果写回 round=0 的 turn。
     *
     * <p><b>评分基准只取「得到答案之前」的回答</b>：run.answerRevealedRound 记录首次
     * 索要答案/提示的轮次（V14 迁移 + chat 端点写入），该轮之后的回答可能是照着答案复述，
     * 不计入量化评分；从未索要则拼接全部用户回答。若用户一上来就索要答案（没有揭示前的作答），
     * 回退到整轮对话判分，让判分如实给出低分，而非报错。
     *
     * @param userId 当前用户
     * @param runId  作答 ID
     * @return 判分结果
     */
    @Transactional
    public GradeView finish(Long userId, Long runId) {
        DrillRun run = runRepo.findByUserIdAndId(userId, runId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "作答不存在"));
        if (run.getStatus() == DrillRunStatus.GRADED) {
            throw new ResponseStatusException(BAD_REQUEST, "该作答已评分");
        }
        if (run.getStatus() != DrillRunStatus.READY && run.getStatus() != DrillRunStatus.ANSWERING) {
            throw new ResponseStatusException(BAD_REQUEST, "当前作答状态不可评分: " + run.getStatus());
        }

        QuestionBank q = qbRepo.findById(run.getQuestionId())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "题目不存在"));

        List<DrillTurn> turns = turnRepo.findByRunIdOrderByRoundAsc(runId);
        if (turns.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "尚无对话记录，无法评分");
        }

        // —— 评分基准：只取「得到答案之前」的回答 ——
        Integer revealRound = run.getAnswerRevealedRound();
        List<DrillTurn> gradeTurns = turns;
        if (revealRound != null) {
            List<DrillTurn> preReveal = turns.stream()
                    .filter(t -> t.getRound() < revealRound)
                    .toList();
            boolean preRevealHasAnswer = preReveal.stream()
                    .anyMatch(t -> t.getRawAnswer() != null && !t.getRawAnswer().isBlank());
            if (preRevealHasAnswer) gradeTurns = preReveal;
        }

        // 把所有用户回答拼接成 combined answer 供 grader 判分。
        // 聊天不限轮数，若整段对话过长，判分 LLM 会撑爆上下文 → 500；
        // 这里保留最近 MAX 字符（近期内容对判分最相关），避免因长对话而崩溃。
        int MAX_COMBINED = 8000;
        StringBuilder combined = new StringBuilder();
        for (DrillTurn t : gradeTurns) {
            if (t.getRawAnswer() != null && !t.getRawAnswer().isBlank()) {
                if (!combined.isEmpty()) combined.append("\n\n");
                combined.append(t.getRawAnswer());
            }
        }
        if (combined.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "用户尚未作答，无法评分");
        }
        if (combined.length() > MAX_COMBINED) {
            combined = new StringBuilder(
                    combined.substring(combined.length() - MAX_COMBINED))
                    .insert(0, "…（对话过长，仅保留最近部分）\n\n");
        }
        String rawAnswer = combined.toString();

        boolean timed = run.getTiming() != null && !run.getTiming().equals("NONE");

        // 对话实录：老师实际问过的问题，供判分器把「没被问到的评分点」判 NA（不计分）。
        // 修复：出题时预生成的评分点覆盖 stem + 全部追问，但对话未必把所有追问都问完；
        // 不区分的话，没问到的点会被误判 MISS，把分数压到很低。
        String conversation = buildConversation(gradeTurns);

        Grader.GraderOutput out;
        if (q.getResponseFormat() == ResponseFormat.FREE_TEXT
                || q.getResponseFormat() == ResponseFormat.STRUCTURED) {
            out = graderText.gradeWithConversation(runId, q, rawAnswer, timed, conversation);
        } else if (q.getResponseFormat() == ResponseFormat.CHOICE) {
            out = graderMcq.grade(runId, q, rawAnswer, timed);
        } else {
            throw new ResponseStatusException(NOT_IMPLEMENTED, "CODE 待接力扣判题");
        }

        // 落 GradeResult
        GradeResult gr = new GradeResult();
        gr.setRunId(runId);
        gr.setQuestionId(q.getId());
        gr.setAnswerHash(Integer.toHexString(rawAnswer.hashCode()));
        gr.setByConceptJson(out.byConceptJson());
        gr.setRawScore(out.rawScore());
        gr.setGrade(out.grade());
        gradeRepo.save(gr);

        // 把判分结果写回 round=0 的 turn（不新建 turn，turns 已由 /chat 创建）
        DrillTurn turn0 = turns.stream()
                .filter(t -> t.getRound() == 0)
                .findFirst()
                .orElse(turns.get(0));
        turn0.setPointsJson(q.getPointsJson());
        turn0.setByConceptJson(out.byConceptJson());
        turn0.setRawScore(out.rawScore());
        turnRepo.save(turn0);

        applyMastery(userId, out.conceptScores(), run.getMode(), timed);

        run.setStatus(DrillRunStatus.GRADED);
        runRepo.save(run);

        return new GradeView(runId, q.getId(),
                out.rawScore() == null ? 0 : out.rawScore().doubleValue(),
                out.grade().name(), out.byConceptJson());
    }

    /** 把整段对话拼成「老师问 / 学生答」实录，供判分器判断哪些评分点被实际考到 */
    private String buildConversation(List<DrillTurn> turns) {
        StringBuilder sb = new StringBuilder();
        for (DrillTurn t : turns) {
            String tutor = t.getTutorText();
            if (tutor != null && !tutor.isBlank()) {
                sb.append("老师：").append(truncate(tutor, 400)).append("\n");
            }
            String ans = t.getRawAnswer();
            if (ans != null && !ans.isBlank()) {
                sb.append("学生：").append(truncate(ans, 400)).append("\n");
            }
        }
        return sb.toString();
    }

    private String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max) + "…";
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
