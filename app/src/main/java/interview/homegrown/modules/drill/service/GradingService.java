package interview.homegrown.modules.drill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.homegrown.modules.drill.domain.ConceptRole;
import interview.homegrown.modules.drill.domain.DrillMode;
import interview.homegrown.modules.drill.domain.DrillPhase;
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
    private final SubPointPassService subPointPassService;
    private final ObjectMapper objectMapper;

    public GradingService(GradeResultRepository gradeRepo, DrillRunRepository runRepo,
                          QuestionBankRepository qbRepo, MasteryRepository masteryRepo,
                          DrillTurnRepository turnRepo,
                          GraderText graderText, GraderMcq graderMcq, ScheduleService scheduleService,
                          SubPointPassService subPointPassService,
                          ObjectMapper objectMapper) {
        this.gradeRepo = gradeRepo;
        this.runRepo = runRepo;
        this.qbRepo = qbRepo;
        this.masteryRepo = masteryRepo;
        this.turnRepo = turnRepo;
        this.graderText = graderText;
        this.graderMcq = graderMcq;
        this.scheduleService = scheduleService;
        this.subPointPassService = subPointPassService;
        this.objectMapper = objectMapper;
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

        applyMastery(userId, out.conceptScores(), run.getMode(), timed,
                run.isGuided(), run.getGuideRounds(), run.isRevealed());

        // 苏格拉底 G1 预引导分：达标（GOOD/EASY）→ DONE + finalGrade；未达标 → GUIDED + preGrade（待引导/再考查）
        Grade g1 = out.grade();
        run.setPreGrade(g1.name());
        if (run.isRevealed()) {
            run.setFinalGrade(Grade.AGAIN.name());
            run.setSocraticState(DrillPhase.DONE);
        } else if (g1 == Grade.GOOD || g1 == Grade.EASY) {
            run.setFinalGrade(g1.name());
            run.setSocraticState(DrillPhase.DONE);
            // 答对达标：该题涉及的所有概念的子知识点自动标记为通过
            subPointPassService.markAllSubPointsPassed(userId, runId, q.getId());
        } else {
            run.setSocraticState(DrillPhase.GUIDED);
        }
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
     * 而是取「首次独立作答」作为评分正文交给 grader，追问/修正只进对话实录供判分器参考，
     * 最后把判分结果写回 round=0 的 turn。
     *
     * <p><b>评分基准只取「显式点击看答案之前」的回答</b>：答案揭示属于不可逆操作，不能依赖关键词猜测用户意图。
     * 诸如“怎么实现”“直接把列表转为集合就行”既可能出现在索要答案中，也可能只是正常作答。
     * 服务端因此只信任前端「看答案」按钮发送的显式 {@code reveal=true}，普通聊天文本永远不会改变揭示边界。
     *
     * @param userId 当前用户
     * @param runId 作答 ID
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

        // —— 评分基准：全部对话（看答案后封 AGAIN，不再按轮次截断） ——
        List<DrillTurn> gradeTurns = turns;
        boolean revealed = run.isRevealed();

        // —— 评分正文取「AI 给出引导提示之前」的独立作答 ——
        // 苏格拉底流程：用户先独立作答 → judge 判 needs_guide → AI 给引导提示 → 用户照提示补全。
        // 引导后的补全不是独立作答（学生看到了提示，含老师泄底风险），不能直接计入评分。
        // 正确取法：找到第一轮 judgeState==needs_guide 的 turn（= AI 首次提示「未达标并引导」那轮），
        // 取它之前的最后一个用户作答（被提示前独立完成的最终答案）。
        // 若全程无 needs_guide（用户独立答对）→ 取最后一轮作答；若首轮即 needs_guide → 取第一轮作答。
        int MAX_COMBINED = 8000;
        String rawAnswer = independentAnswerBeforeFirstGuide(gradeTurns);
        if (rawAnswer == null || rawAnswer.isBlank()) {
            rawAnswer = gradeTurns.stream()
                    .filter(t -> t.getRawAnswer() != null && !t.getRawAnswer().isBlank())
                    .map(DrillTurn::getRawAnswer)
                    .reduce((first, second) -> second)
                    .orElse(null);
        }
        if (rawAnswer == null || rawAnswer.isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "用户尚未作答，无法评分");
        }
        if (rawAnswer.length() > MAX_COMBINED) {
            rawAnswer = rawAnswer.substring(0, MAX_COMBINED) + "…（作答过长，仅保留开头）";
        }

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

        applyMastery(userId, out.conceptScores(), run.getMode(), timed,
                run.isGuided(), run.getGuideRounds(), run.isRevealed());

        // 苏格拉底 G1 预引导分（延迟评分路径同 grade）
        Grade g1 = out.grade();
        run.setPreGrade(g1.name());
        if (revealed) {
            // 看过答案 → 封 AGAIN
            run.setFinalGrade(Grade.AGAIN.name());
            run.setSocraticState(DrillPhase.DONE);
        } else if (g1 == Grade.GOOD || g1 == Grade.EASY) {
            run.setFinalGrade(g1.name());
            run.setSocraticState(DrillPhase.DONE);
            // 答对达标：该题涉及的所有概念的子知识点自动标记为通过
            subPointPassService.markAllSubPointsPassed(userId, runId, q.getId());
        } else {
            run.setSocraticState(DrillPhase.GUIDED);
        }
        run.setStatus(DrillRunStatus.GRADED);
        runRepo.save(run);

        return new GradeView(runId, q.getId(),
                out.rawScore() == null ? 0 : out.rawScore().doubleValue(),
                out.grade().name(), out.byConceptJson());
    }

    /**
     * 苏格拉底 G2 引导后达标结算：SocraticJudge 已判 done（覆盖≥80%且无致命缺漏）。
     * 落 GradeResult + applyMastery，最终分=GOOD（封顶，不给 EASY），覆盖 G1 的 preGrade。
     * 用于 chat 流里 needs_guide 之后经引导再答完（done）的路径。
     *
     * @return 落库后的 GradeView（rawScore 由 coverage 折算，满 100）
     */
    @Transactional
    public GradeView guidedPass(Long userId, Long runId, DrillTurn doneTurn) {
        DrillRun run = runRepo.findByUserIdAndId(userId, runId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "作答不存在"));
        if (run.getStatus() == DrillRunStatus.GRADED) {
            throw new ResponseStatusException(BAD_REQUEST, "该作答已评分");
        }
        QuestionBank q = qbRepo.findById(run.getQuestionId())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "题目不存在"));

        boolean timed = run.getTiming() != null && !run.getTiming().equals("NONE");
        double coverage = doneTurn.getCoverage() == null ? 0.8 : doneTurn.getCoverage().doubleValue();
        double rawScore = Math.max(60, Math.min(100, coverage * 100));

        // 落 GradeResult（G2 判定：达标 → GOOD）
        GradeResult gr = new GradeResult();
        gr.setRunId(runId);
        gr.setQuestionId(q.getId());
        gr.setAnswerHash(Integer.toHexString(doneTurn.getRawAnswer() == null ? "".hashCode() : doneTurn.getRawAnswer().hashCode()));
        gr.setByConceptJson("[]");
        gr.setRawScore(java.math.BigDecimal.valueOf(rawScore));
        gr.setGrade(Grade.GOOD);
        gr.setPreGrade(run.getPreGrade());   // 记录 G1 预引导分
        gr.setFinalGrade("GOOD");
        gr.setGuided(true);
        gr.setGuideRounds(run.getGuideRounds());
        gr.setRevealed(run.isRevealed());
        gradeRepo.save(gr);

        // 更新 run：finalGrade=GOOD（覆盖 G1）、DONE、GRADED
        run.setPreGrade(run.getPreGrade());
        run.setFinalGrade(Grade.GOOD.name());
        run.setSocraticState(DrillPhase.DONE);
        run.setStatus(DrillRunStatus.GRADED);
        runRepo.save(run);

        // 答对达标：该题涉及的所有概念的子知识点自动标记为通过
        subPointPassService.markAllSubPointsPassed(userId, runId, q.getId());

        // 掌握度：G2 引导后达标 → 按 primary 概念升到 GOOD（guided 会缩短复习间隔）
        Long primaryId = (q.getConceptIds() == null || q.getConceptIds().length == 0)
                ? null : q.getConceptIds()[0].longValue();
        List<ConceptScore> scores = primaryId == null ? List.of()
                : List.of(new ConceptScore(primaryId, ConceptRole.PRIMARY,
                        java.math.BigDecimal.valueOf(rawScore), Grade.GOOD));
        applyMastery(userId, scores, run.getMode(), timed,
                true, run.getGuideRounds(), run.isRevealed());

        return new GradeView(runId, q.getId(), rawScore, Grade.GOOD.name(), "[]");
    }

    /** 把整段对话拼成「老师问 / 学生答」实录，供判分器判断哪些评分点被实际考到 */
    private String buildConversation(List<DrillTurn> turns) {
        StringBuilder sb = new StringBuilder();
        boolean guideShown = false;   // 是否已进入「AI 引导提示后」阶段
        for (DrillTurn t : turns) {
            String judgeState = t.getJudgeState();
            String ans = t.getRawAnswer();
            if (ans != null && !ans.isBlank()) {
                String stage = guideShown ? "（已给引导提示后）" : "（独立作答）";
                sb.append("学生第 ").append(t.getRound() + 1).append(" 轮").append(stage).append("：")
                        .append(trimForGrading(ans)).append("\n");
            }
            // 该轮被判 needs_guide → 此后进入「引导后」阶段
            if ("needs_guide".equalsIgnoreCase(judgeState)) {
                guideShown = true;
            }
            String tutor = t.getTutorText();
            if (tutor != null && !tutor.isBlank()) {
                sb.append("老师：").append(trimForGrading(tutor)).append("\n");
            }
        }
        return sb.toString();
    }

    /** 供对话实录用的裁剪：代码作答（含 ``` 围栏）完整保留；其余宽松截断到 1200 字符。 */
    private static String trimForGrading(String s) {
        if (s == null || s.length() <= 1200) return s == null ? "" : s;
        if (s.contains("```")) return s;
        return s.substring(0, 1200) + "…";
    }

    /**
     * 取「AI 首次引导提示之前」的最后一个独立作答。
     * <p>遍历按 round 升序的 turns，找到第一轮 judgeState==needs_guide 的 turn
     * （= AI 提示「未达标并引导」的那轮），取它之前的最后一个用户作答。
     * <ul>
     *   <li>首轮即 needs_guide（引导前无作答）→ 返回第一个用户作答（首答就是唯一的独立作答）</li>
     *   <li>中途出现 needs_guide → 返回引导前最后一个作答</li>
     *   <li>全程无 needs_guide（用户独立完成）→ 返回 null，由调用方回退取最后一轮</li>
     * </ul>
     */
    private static String independentAnswerBeforeFirstGuide(List<DrillTurn> turns) {
        if (turns == null || turns.isEmpty()) return null;
        String lastBeforeGuide = null;
        String firstAnswer = null;
        for (DrillTurn t : turns) {
            String ans = t.getRawAnswer();
            if (ans != null && !ans.isBlank()) {
                if (firstAnswer == null) firstAnswer = ans;
                lastBeforeGuide = ans;
            }
            // 这轮被判 needs_guide：AI 即将/已经给出引导提示，之后的作答都算「被提示后」
            if ("needs_guide".equalsIgnoreCase(t.getJudgeState())) {
                return lastBeforeGuide != null ? lastBeforeGuide : firstAnswer;
            }
        }
        return null;   // 全程无 needs_guide → 调用方回退
    }

    /** per concept 更新掌握度与下次复习时间（练习主流程：lean 用苏格拉底评分字段算动态到期） */
    public void applyMastery(Long userId, List<ConceptScore> scores, DrillMode mode, boolean timed,
                             boolean guided, int guideRounds, boolean revealed) {
        for (ConceptScore cs : scores) {
            Grade effective = effectiveGrade(cs);
            int level = computeLevel(userId, cs.conceptId(), effective, cs.role(), mode);
            Instant due = scheduleService.nextDue(effective, timed, guided, guideRounds, revealed);
            masteryRepo.upsert(userId, cs.conceptId(), level, effective.name(), due);
        }
    }

    /** per concept 更新掌握度与下次复习时间（模拟面试 REHEARSAL 路径，无苏格拉底引导概念） */
    public void applyMastery(Long userId, List<ConceptScore> scores, DrillMode mode, boolean timed) {
        applyMastery(userId, scores, mode, timed, false, 0, false);
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
