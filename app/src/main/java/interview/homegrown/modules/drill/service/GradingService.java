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
    private final ObjectMapper objectMapper;

    public GradingService(GradeResultRepository gradeRepo, DrillRunRepository runRepo,
                          QuestionBankRepository qbRepo, MasteryRepository masteryRepo,
                          DrillTurnRepository turnRepo,
                          GraderText graderText, GraderMcq graderMcq, ScheduleService scheduleService,
                          ObjectMapper objectMapper) {
        this.gradeRepo = gradeRepo;
        this.runRepo = runRepo;
        this.qbRepo = qbRepo;
        this.masteryRepo = masteryRepo;
        this.turnRepo = turnRepo;
        this.graderText = graderText;
        this.graderMcq = graderMcq;
        this.scheduleService = scheduleService;
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

        applyMastery(userId, out.conceptScores(), run.getMode(), timed);

        // 三阶段练习：阶段1（独立作答）判分锁定基础档位，进入教学讲解阶段。
        // 之后阶段3（补救测试）答对可「降级通过」升级（封顶 GOOD），答错不降级。
        run.setFirstGrade(out.grade().name());
        run.setPhase(DrillPhase.TUTORING);
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

        // —— 评分正文只取「第一次独立作答」 ——
        // 追问后的修正/复述只进入 conversation 供判分器参考（判断老师是否泄底、哪些点被实际考到），
        // 不进入评分正文。否则老师纠正后学生照着复述出正确版本，会被判 HIT 把分数刷成满分，失去考察意义。
        // gradeTurns 已按 round 升序，findFirst 即第一次作答。
        int MAX_COMBINED = 8000;
        String rawAnswer = gradeTurns.stream()
                .filter(t -> t.getRawAnswer() != null && !t.getRawAnswer().isBlank())
                .map(DrillTurn::getRawAnswer)
                .findFirst()
                .orElse(null);
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

        applyMastery(userId, out.conceptScores(), run.getMode(), timed);

        // 三阶段练习：对话判分同样锁定基础档位并进入讲解阶段（补救测试入口由前端按档位展示）
        run.setFirstGrade(out.grade().name());
        run.setPhase(DrillPhase.TUTORING);
        run.setStatus(DrillRunStatus.GRADED);
        runRepo.save(run);

        return new GradeView(runId, q.getId(),
                out.rawScore() == null ? 0 : out.rawScore().doubleValue(),
                out.grade().name(), out.byConceptJson());
    }

    /**
     * 阶段3（补救测试）判分：基于 run 上暂存的补救题（transferStem/transferPointsJson/transferConceptIdsJson）
     * 判分，只允许「降级通过」——答对升一档（AGAIN→HARD→GOOD，封顶 GOOD），答错不降级。
     * 判分落库后：更新 first_grade、transfer_count 递增；达到上限或升到 GOOD 则 phase=DONE。
     *
     * @return 补救测试判分结果（grade 为升级后的档位）
     */
    @Transactional
    public GradeView gradeTransfer(Long userId, Long runId, String rawAnswer) {
        DrillRun run = runRepo.findByUserIdAndId(userId, runId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "作答不存在"));
        if (run.getPhase() != DrillPhase.TRANSFER_TEST) {
            throw new ResponseStatusException(BAD_REQUEST, "当前不在补救测试阶段: " + run.getPhase());
        }
        if (run.getStatus() != DrillRunStatus.GRADED) {
            throw new ResponseStatusException(BAD_REQUEST, "当前作答状态不可补救测试: " + run.getStatus());
        }
        // 看答案后不再追问：阶段3 已揭示答案，继续考已无意义
        if (run.getAnswerRevealedRound() != null) {
            throw new ResponseStatusException(BAD_REQUEST, "已看答案，不再继续追问");
        }
        String stem = run.getTransferStem();
        String pointsJson = run.getTransferPointsJson();
        Integer[] conceptIds = parseConceptIds(run.getTransferConceptIdsJson());
        if (stem == null || pointsJson == null || conceptIds == null) {
            throw new ResponseStatusException(BAD_REQUEST, "补救测试题不存在");
        }
        boolean timed = run.getTiming() != null && !run.getTiming().equals("NONE");

        Grader.GraderOutput out = graderText.gradeRaw(stem, pointsJson, conceptIds, rawAnswer, timed);

        // 降级通过：答对（GOOD/EASY）→ 基础档位升一档（封顶 GOOD）；答错 → 保持基础档位（不降级）
        String base = run.getFirstGrade() == null ? "AGAIN" : run.getFirstGrade();
        Grade upgraded = switch (out.grade()) {
            case AGAIN, HARD -> Grade.valueOf(base);       // 补救测试未通过：维持阶段1档位
            case GOOD, EASY -> upgradeOnPass(base);        // 通过：升一档，封顶 GOOD
        };
        boolean passed = out.grade() == Grade.GOOD || out.grade() == Grade.EASY;
        run.setFirstGrade(upgraded.name());
        run.setTransferCount(run.getTransferCount() + 1);
        // —— 动态追问轮数（需求：次数根据回答质量动态决定，不写死）——
        // 答对 → 结束；答错 → 按本轮得分决定还能不能再考：
        //   rawScore>=50（接近通过）→ 允许最多 3 轮；30~50（部分理解）→ 2 轮；
        //   <30（完全没答对）→ 本轮即止。硬上限 3 轮防死循环。
        if (passed) {
            run.setPhase(DrillPhase.DONE);
        } else {
            int dynamicMax = dynamicTransferMax(
                    out.rawScore() == null ? 0 : out.rawScore().doubleValue(), base);
            run.setTransferMax(dynamicMax); // 持久化动态上限，前端/后续读一致
            run.setPhase(run.getTransferCount() >= dynamicMax
                    ? DrillPhase.DONE
                    : DrillPhase.TUTORING);
        }
        runRepo.save(run);

        boolean transferExhausted = run.getPhase() == DrillPhase.DONE && !passed;

        // 补救测试通过 → 更新阶段1 落的那条 GradeResult 为「最终成绩」（升级后档位）：
        // 让「子点通过」判定（findPassedFocusedRuns 按 GradeResult.rawScore >= 60）能看到降级通过，
        // 否则 HARD 降级通过的 run 会因阶段1 分数不过线而永远不算达标。
        // 同时保持「一 run 一条 GradeResult」不变式——history/review/note 都用
        // gradeRepo.findByRunId()（Optional 单条），再落一条会让它们抛 IncorrectResultSize。
        if (passed) {
            GradeResult gr = gradeRepo.findByRunId(runId).orElse(null);
            if (gr != null) {
                gr.setAnswerHash(Integer.toHexString(rawAnswer.hashCode()));
                gr.setByConceptJson(out.byConceptJson());
                gr.setRawScore(out.rawScore());
                gr.setGrade(upgraded);
                gradeRepo.save(gr);
            }
        }

        // 补救测试通过 → 掌握度结算（降级通过）：
        // 阶段1 独立作答已按基础档位 applyMastery 过一次（AGAIN 会把 level 降一档）。
        // 补救测试通过后 PRIMARY 概念 level 直接 +1（封顶 cap）：
        //   AGAIN→HARD 通过 = 恢复到阶段1 之前的 level；
        //   HARD→GOOD 通过 = 提升一级。
        // ANCHOR（已掌握概念）只是陪考，不因补救测试改变掌握度。
        if (passed && out.conceptScores() != null && !out.conceptScores().isEmpty()) {
            ConceptScore primary = out.conceptScores().get(0);
            if (primary.role() == ConceptRole.PRIMARY) {
                Mastery m = masteryRepo.findByUserIdAndConceptId(userId, primary.conceptId()).orElse(null);
                int cur = m == null ? 0 : m.getMasteryLevel();
                int cap = 2; // LEARN 概念级封顶 L2
                int newLevel = Math.min(cap, cur + 1);
                Instant due = scheduleService.nextDue(upgraded, timed);
                masteryRepo.upsert(userId, primary.conceptId(), newLevel, upgraded.name(), due);
            }
        }

        return new GradeView(runId, null,
                out.rawScore() == null ? 0 : out.rawScore().doubleValue(),
                upgraded.name(), out.byConceptJson(), transferExhausted);
    }

    /**
     * 动态补救测试轮数上限（按本轮作答质量 + 基础档位）：
     * <p>得分反映本轮理解，基础档位反映初始掌握：
     * <ul>
     *   <li>rawScore &gt;= 50（接近通过）→ 差一点就能过：HARD 允许最多 3 轮，AGAIN 最多 2 轮；</li>
     *   <li>30 &lt;= rawScore &lt; 50（部分理解）→ 有点基础：最多 2 轮；</li>
     *   <li>rawScore &lt; 30（几乎没答对）→ 再问也无意义：本轮即止（1 轮）。</li>
     * </ul>
     * 无论怎样硬上限 3 轮，防止 AI 无限出题（用户决策：追问要设置上限）。
     */
    private int dynamicTransferMax(double rawScore, String baseGrade) {
        if (rawScore >= 50) {
            return "HARD".equals(baseGrade) ? 3 : 2; // 差一点 + 原本就接近 → 最宽容
        }
        if (rawScore >= 30) {
            return 2;
        }
        return 1;
    }

    /** 补救测试通过时把基础档位升一档：AGAIN→HARD→GOOD，GOOD 封顶（EASY 保持）。 */
    private Grade upgradeOnPass(String base) {
        return switch (base) {
            case "AGAIN" -> Grade.HARD;
            case "HARD" -> Grade.GOOD;
            case "GOOD", "EASY" -> Grade.GOOD;
            default -> Grade.GOOD;
        };
    }

    private Integer[] parseConceptIds(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, Integer[].class);
        } catch (Exception e) {
            return null;
        }
    }

    /** 把整段对话拼成「老师问 / 学生答」实录，供判分器判断哪些评分点被实际考到 */
    private String buildConversation(List<DrillTurn> turns) {
        StringBuilder sb = new StringBuilder();
        boolean firstAnswerSeen = false;
        for (DrillTurn t : turns) {
            String ans = t.getRawAnswer();
            if (ans != null && !ans.isBlank()) {
                // 明确区分「首次独立作答」与「后续追问/修正」：后续修正不进入评分正文，
                // 这里标注出来，避免判分器把老师纠正后的复述当成独立作答采信。
                String label = firstAnswerSeen ? "学生（追问后的修正/复述，不计分）" : "学生（首次独立作答，计分）";
                firstAnswerSeen = true;
                sb.append(label).append("：").append(truncate(ans, 400)).append("\n");
            }
            String tutor = t.getTutorText();
            if (tutor != null && !tutor.isBlank()) {
                sb.append("老师：").append(truncate(tutor, 400)).append("\n");
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
