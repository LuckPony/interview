package interview.homegrown.modules.drill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.homegrown.modules.drill.ai.FollowupGenerator;
import interview.homegrown.modules.drill.ai.GeneratedQuestion;
import interview.homegrown.modules.drill.domain.Concept;
import interview.homegrown.modules.drill.domain.ConceptRole;
import interview.homegrown.modules.drill.domain.DrillMode;
import interview.homegrown.modules.drill.domain.DrillRun;
import interview.homegrown.modules.drill.domain.DrillRunStatus;
import interview.homegrown.modules.drill.domain.DrillTurn;
import interview.homegrown.modules.drill.domain.Grade;
import interview.homegrown.modules.drill.domain.GradeResult;
import interview.homegrown.modules.drill.domain.Mastery;
import interview.homegrown.modules.drill.domain.QuestionBank;
import interview.homegrown.modules.drill.domain.SelectedTask;
import interview.homegrown.modules.drill.grader.ConceptScore;
import interview.homegrown.modules.drill.grader.GradeScale;
import interview.homegrown.modules.drill.grader.Grader;
import interview.homegrown.modules.drill.grader.GraderText;
import interview.homegrown.modules.drill.repository.ConceptRepository;
import interview.homegrown.modules.drill.repository.DrillRunRepository;
import interview.homegrown.modules.drill.repository.DrillTurnRepository;
import interview.homegrown.modules.drill.repository.GradeResultRepository;
import interview.homegrown.modules.drill.repository.MasteryRepository;
import interview.homegrown.modules.drill.repository.QuestionBankRepository;
import interview.homegrown.modules.drill.web.dto.RehearsalView;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * 模拟面试（痛点 4 的无语音版）。
 *
 * <p>"说不出来"的真正病根不在嗓子，而在<b>无参考资料 + 时间压力下提取不出来、组织不出来、
 * 扛不住追问</b>。这三件事纯文本就能练，所以这里零新增判分逻辑，全部复用已有零件：
 * 选题走 SelectionService、出题走 QuestionService、判分走 GraderText、排程走 ScheduleService。
 *
 * <p>三条硬规则：
 * <ol>
 *   <li><b>Write→Speak 闸门</b>：mastery_level&gt;=2（写达标）才允许进，否则会变成边想边编。</li>
 *   <li><b>闭卷 + 计时</b>：open_book=false、timing=COUNTDOWN，这是"压力"的来源。</li>
 *   <li><b>追问封顶 2 轮</b>：轮数由服务端决定，LLM 只决定这一轮问什么，防无限递归。</li>
 * </ol>
 * mastery L3 = 模拟面试达标（全部轮 content 通过），只有这条路能发 L3。
 */
@Service
public class RehearsalService {

    /** 进入模拟面试的门槛：写模式已达标 */
    private static final int ENTRY_LEVEL = 2;
    /** 追问封顶轮数（不含主问） */
    private static final int MAX_FOLLOWUP = 2;

    private final SelectionService selectionService;
    private final QuestionService questionService;
    private final FollowupGenerator followupGenerator;
    private final GraderText graderText;
    private final GradingService gradingService;
    private final DrillRunRepository runRepo;
    private final DrillTurnRepository turnRepo;
    private final QuestionBankRepository qbRepo;
    private final MasteryRepository masteryRepo;
    private final ConceptRepository conceptRepo;
    private final GradeResultRepository gradeRepo;
    private final ObjectMapper objectMapper;

    public RehearsalService(SelectionService selectionService, QuestionService questionService,
                            FollowupGenerator followupGenerator, GraderText graderText,
                            GradingService gradingService, DrillRunRepository runRepo,
                            DrillTurnRepository turnRepo, QuestionBankRepository qbRepo,
                            MasteryRepository masteryRepo, ConceptRepository conceptRepo,
                            GradeResultRepository gradeRepo, ObjectMapper objectMapper) {
        this.selectionService = selectionService;
        this.questionService = questionService;
        this.followupGenerator = followupGenerator;
        this.graderText = graderText;
        this.gradingService = gradingService;
        this.runRepo = runRepo;
        this.turnRepo = turnRepo;
        this.qbRepo = qbRepo;
        this.masteryRepo = masteryRepo;
        this.conceptRepo = conceptRepo;
        this.gradeRepo = gradeRepo;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------- start

    @Transactional
    public RehearsalView start(Long userId, Long conceptId) {
        Long target = conceptId != null ? conceptId : pickEligibleConcept(userId);
        assertEligible(userId, target);

        SelectedTask task = selectionService.pickFor(userId, target);
        QuestionBank q = questionService.generate(task);

        DrillRun run = new DrillRun();
        run.setUserId(userId);
        run.setQuestionId(q.getId());
        run.setMode(DrillMode.REHEARSAL);
        run.setStatus(DrillRunStatus.ANSWERING);
        run.setOpenBook(false);                 // 闭卷：模拟面试没有资料可翻
        run.setTiming("COUNTDOWN");             // 计时：压力来源，也让 EASY 档可达
        run.setCurrentRound(0);
        run.setMaxRound(MAX_FOLLOWUP);
        try {
            run = runRepo.save(run);
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(CONFLICT, "已有未完成的作答，请先完成或搁置");
        }

        DrillTurn turn = new DrillTurn();
        turn.setRunId(run.getId());
        turn.setRound(0);
        turn.setStem(q.getStem());
        turn.setPointsJson(q.getPointsJson());
        turnRepo.save(turn);

        return RehearsalView.asking(run.getId(), 0, MAX_FOLLOWUP, q.getStem());
    }

    /** 挑一个够格进模拟面试的概念：已达 L2、最久没练的优先 */
    private Long pickEligibleConcept(Long userId) {
        return masteryRepo.findByUserId(userId).stream()
                .filter(m -> m.getMasteryLevel() >= ENTRY_LEVEL)
                .min(Comparator.comparing(m -> m.getDueAt() == null ? java.time.Instant.EPOCH : m.getDueAt()))
                .map(Mastery::getConceptId)
                .orElseThrow(() -> new ResponseStatusException(FORBIDDEN,
                        "还没有任何概念达到写模式达标（L2），先去 LEARN 模式练"));
    }

    /** 规则 1：Write→Speak 闸门 */
    private void assertEligible(Long userId, Long conceptId) {
        int level = masteryRepo.findByUserIdAndConceptId(userId, conceptId)
                .map(Mastery::getMasteryLevel).orElse(0);
        if (level < ENTRY_LEVEL) {
            throw new ResponseStatusException(FORBIDDEN,
                    "该概念写模式尚未达标（当前 L" + level + "，需 L" + ENTRY_LEVEL + "）");
        }
    }

    // --------------------------------------------------------------- answer

    @Transactional
    public RehearsalView answer(Long userId, Long runId, String rawAnswer) {
        DrillRun run = runRepo.findByUserIdAndId(userId, runId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "作答不存在"));
        if (run.getMode() != DrillMode.REHEARSAL) {
            throw new ResponseStatusException(BAD_REQUEST, "该作答不是模拟面试模式");
        }
        if (run.getStatus() != DrillRunStatus.READY && run.getStatus() != DrillRunStatus.ANSWERING) {
            throw new ResponseStatusException(BAD_REQUEST, "当前作答状态不可提交: " + run.getStatus());
        }

        QuestionBank q = qbRepo.findById(run.getQuestionId())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "题目不存在"));
        DrillTurn turn = turnRepo.findByRunIdAndRound(runId, run.getCurrentRound())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "本轮问题不存在"));

        // 闭卷 + 计时 -> timed 恒为 true，EASY 档在模拟面试里是可达的
        Grader.GraderOutput out = graderText.gradeRaw(
                turn.getStem(), turn.getPointsJson(), q.getConceptIds(), rawAnswer, true);

        turn.setRawAnswer(rawAnswer);
        turn.setByConceptJson(out.byConceptJson());
        turn.setRawScore(out.rawScore());
        turn.setPassed(GradeScale.passed(out.rawScore()));
        turnRepo.save(turn);

        boolean roundsLeft = run.getCurrentRound() < run.getMaxRound();
        // 本轮没过就不再追问：连当前这层都答不上来，继续深挖只是重复羞辱，没有训练价值
        if (roundsLeft && Boolean.TRUE.equals(turn.getPassed())) {
            return askFollowup(run, q, turn);
        }
        return settle(userId, run, q);
    }

    private RehearsalView askFollowup(DrillRun run, QuestionBank q, DrillTurn prev) {
        int nextRound = run.getCurrentRound() + 1;
        List<String> names = conceptNames(q);

        GeneratedQuestion fq = followupGenerator.generate(
                prev.getStem(), prev.getRawAnswer(), names, nextRound);

        DrillTurn next = new DrillTurn();
        next.setRunId(run.getId());
        next.setRound(nextRound);
        next.setStem(fq.stem);
        next.setPointsJson(graderText.wrapPoints(fq));
        turnRepo.save(next);

        run.setCurrentRound(nextRound);
        runRepo.save(run);

        return RehearsalView.asking(run.getId(), nextRound, run.getMaxRound(), fq.stem);
    }

    /** 结算：全部轮通过才发 L3；任一轮没过则按平均分正常升降 */
    private RehearsalView settle(Long userId, DrillRun run, QuestionBank q) {
        List<DrillTurn> turns = turnRepo.findByRunIdOrderByRoundAsc(run.getId());
        List<DrillTurn> answered = turns.stream().filter(t -> t.getRawAnswer() != null).toList();

        BigDecimal avg = average(answered);
        boolean allPassed = !answered.isEmpty()
                && answered.stream().allMatch(t -> Boolean.TRUE.equals(t.getPassed()));

        // 没扛住追问就不能拿 L3：把档位压到 GOOD 以下的边界由 avg 决定，但 allPassed 是硬门槛
        Grade grade = allPassed ? GradeScale.toGrade(avg, true) : capBelowUpgrade(avg);

        GradeResult gr = new GradeResult();
        gr.setRunId(run.getId());
        gr.setQuestionId(q.getId());
        gr.setAnswerHash(Integer.toHexString(joinAnswers(answered).hashCode()));
        gr.setByConceptJson(mergeByConcept(answered));
        gr.setRawScore(avg);
        gr.setGrade(grade);
        gradeRepo.save(gr);

        List<ConceptScore> scores = buildConceptScores(q, avg, grade);
        gradingService.applyMastery(userId, scores, DrillMode.REHEARSAL, true);

        run.setStatus(DrillRunStatus.GRADED);
        runRepo.save(run);

        List<String> roundScores = answered.stream()
                .map(t -> "R" + t.getRound() + "=" + (t.getRawScore() == null ? "-" : t.getRawScore()))
                .toList();

        return new RehearsalView(run.getId(), run.getCurrentRound(), run.getMaxRound(), null,
                true, avg.doubleValue(), grade.name(), allPassed, roundScores,
                mergeByConcept(answered));
    }

    /** 未全通过时最多给 HARD：保住"不升级"，但也不冤枉地打成 AGAIN */
    private Grade capBelowUpgrade(BigDecimal avg) {
        Grade g = GradeScale.toGrade(avg, true);
        return (g == Grade.GOOD || g == Grade.EASY) ? Grade.HARD : g;
    }

    private List<ConceptScore> buildConceptScores(QuestionBank q, BigDecimal avg, Grade grade) {
        List<ConceptScore> scores = new ArrayList<>();
        Integer[] cids = q.getConceptIds();
        for (int i = 0; i < cids.length; i++) {
            scores.add(new ConceptScore(cids[i].longValue(),
                    i == 0 ? ConceptRole.PRIMARY : ConceptRole.ANCHOR,
                    avg, grade));
        }
        return scores;
    }

    private BigDecimal average(List<DrillTurn> turns) {
        if (turns.isEmpty()) return BigDecimal.ZERO;
        BigDecimal sum = BigDecimal.ZERO;
        for (DrillTurn t : turns) {
            sum = sum.add(t.getRawScore() == null ? BigDecimal.ZERO : t.getRawScore());
        }
        return sum.divide(BigDecimal.valueOf(turns.size()), 2, RoundingMode.HALF_UP);
    }

    private String joinAnswers(List<DrillTurn> turns) {
        return turns.stream().map(DrillTurn::getRawAnswer).reduce("", (a, b) -> a + "\n" + b);
    }

    /** 把各轮 by_concept 拼成一个数组，保留全场判分留痕 */
    private String mergeByConcept(List<DrillTurn> turns) {
        List<Object> all = new ArrayList<>();
        for (DrillTurn t : turns) {
            if (t.getByConceptJson() == null) continue;
            try {
                all.add(objectMapper.readTree(t.getByConceptJson()));
            } catch (Exception ignored) {
                // 单轮解析失败不影响整体结算
            }
        }
        try {
            return objectMapper.writeValueAsString(all);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<String> conceptNames(QuestionBank q) {
        List<Long> ids = new ArrayList<>();
        for (Integer i : q.getConceptIds()) {
            ids.add(i.longValue());
        }
        return conceptRepo.findAllById(ids).stream().map(Concept::getName).toList();
    }
}
