package interview.homegrown.modules.drill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.homegrown.modules.drill.ai.GeneratedQuestion;
import interview.homegrown.modules.drill.domain.DrillMode;
import interview.homegrown.modules.drill.domain.DrillNote;
import interview.homegrown.modules.drill.domain.DrillRun;
import interview.homegrown.modules.drill.domain.DrillRunStatus;
import interview.homegrown.modules.drill.domain.DrillTurn;
import interview.homegrown.modules.drill.domain.GradeResult;
import interview.homegrown.modules.drill.grader.GradeScale;
import interview.homegrown.modules.drill.repository.DrillNoteRepository;
import interview.homegrown.modules.drill.repository.DrillRunRepository;
import interview.homegrown.modules.drill.repository.DrillTurnRepository;
import interview.homegrown.modules.drill.repository.GradeResultRepository;
import interview.homegrown.modules.drill.repository.QuestionBankRepository;
import interview.homegrown.modules.drill.web.dto.DebtView;
import interview.homegrown.modules.drill.web.dto.NoteRequest;
import interview.homegrown.modules.drill.web.dto.NoteView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;
// 422：RFC 9110 把 UNPROCESSABLE_ENTITY 更名为 UNPROCESSABLE_CONTENT，Spring 6.2+ 已弃用旧名
import static org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT;

/**
 * 内化笔记。
 *
 * <p>"笔记没过脑子"的机制是：AI 给了一段漂亮总结 -> 用户 Ctrl+C/Ctrl+V -> 产生"我记下了"的
 * 完成感 -> 大脑跳过了提取与重构这两个真正产生记忆的动作。所以解法不能是提示"请用自己的话写"，
 * 提示只是道德劝说，一定会被绕过。这里用四道<b>物理约束</b>：
 *
 * <ol>
 *   <li><b>字段缺席</b>：drill_note 表和 NoteRequest 都没有 summary / correct_answer 列，
 *       想存 AI 的标准答案，在 schema 层面就没地方放。</li>
 *   <li><b>顺序锁</b>：只有 GRADED 的作答才能写笔记。先答后写，杜绝"抄着题干边看边写"。</li>
 *   <li><b>抄写检测</b>：myWords 与题干、评分点做 trigram <b>包含度</b>比对，超阈值直接拒收 422。</li>
 *   <li><b>缺口强制</b>：没过线的题必须写清楚缺口和下一步，否则不收 —— 答错了却说不出错在哪，
 *       就是典型的没过脑子。</li>
 * </ol>
 *
 * <p>另外配一个"内化债务"清单：答错且没复盘的题会挂在 {@code GET /drill/debt} 上，
 * 供「内化复盘」页自选消化。<b>不设硬闸门</b>（用户 2026-08-10 决策）：欠账不拦截开新题，
 * 学习节奏交给用户自己掌握 —— 但写了笔记的题会从清单消失，形成可感知的正反馈。
 */
@Service
public class NoteService {

    private static final Logger log = LoggerFactory.getLogger(NoteService.class);

    /** myWords 长度下限（字符）。低于这个数基本是"懂了""记住了"式的敷衍 */
    private static final int MIN_WORDS = 40;
    /** gapFound / nextAction 的长度下限 */
    private static final int MIN_GAP = 10;
    /**
     * 抄写判定阈值：笔记中来自题干/评分点的 trigram 占比。
     * 0.35 是经验值 —— 正常复述会自然复用术语（"双亲委派""ReadView"），占比通常 0.1~0.25；
     * 一旦整段搬运会迅速冲到 0.6 以上。定太严会误伤术语密集的短笔记。
     */
    private static final double COPY_THRESHOLD = 0.35;

    private final DrillRunRepository runRepo;
    private final DrillNoteRepository noteRepo;
    private final DrillTurnRepository turnRepo;
    private final QuestionBankRepository qbRepo;
    private final GradeResultRepository gradeRepo;
    private final SimilarityGuard similarityGuard;
    private final ObjectMapper objectMapper;

    public NoteService(DrillRunRepository runRepo, DrillNoteRepository noteRepo,
                       DrillTurnRepository turnRepo, QuestionBankRepository qbRepo,
                       GradeResultRepository gradeRepo, SimilarityGuard similarityGuard,
                       ObjectMapper objectMapper) {
        this.runRepo = runRepo;
        this.noteRepo = noteRepo;
        this.turnRepo = turnRepo;
        this.qbRepo = qbRepo;
        this.gradeRepo = gradeRepo;
        this.similarityGuard = similarityGuard;
        this.objectMapper = objectMapper;
    }

    // ----------------------------------------------------------- 写笔记

    @Transactional
    public NoteView write(Long userId, Long runId, NoteRequest req) {
        DrillRun run = runRepo.findByUserIdAndId(userId, runId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "作答不存在"));

        // 约束 2：顺序锁
        if (run.getStatus() != DrillRunStatus.GRADED) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "只有判分完成的作答才能写内化笔记，当前状态: " + run.getStatus());
        }
        if (noteRepo.findByRunId(runId).isPresent()) {
            throw new ResponseStatusException(CONFLICT, "这道题已经写过内化笔记了");
        }

        String myWords = trim(req.myWords());
        if (myWords.length() < MIN_WORDS) {
            throw new ResponseStatusException(UNPROCESSABLE_CONTENT,
                    "复述太短（" + myWords.length() + "/" + MIN_WORDS + " 字）："
                            + "把你刚才在脑子里想的过程写出来，而不是写一句结论");
        }

        BigDecimal score = gradeRepo.findByRunId(runId)
                .map(GradeResult::getRawScore).orElse(BigDecimal.ZERO);
        boolean failed = !GradeScale.passed(score);

        // 约束 4：缺口强制
        if (failed) {
            if (trim(req.gapFound()).length() < MIN_GAP) {
                throw new ResponseStatusException(UNPROCESSABLE_CONTENT,
                        "这题没过线（" + score + " 分），必须写清楚缺口在哪：是不知道、想不起来，还是说不清楚？");
            }
            if (trim(req.nextAction()).length() < MIN_GAP) {
                throw new ResponseStatusException(UNPROCESSABLE_CONTENT,
                        "写下一步动作：一句可执行的话（去读哪段源码 / 重做哪个实验），不要写'再复习一遍'");
            }
        }

        // 约束 3：抄写检测
        List<String> sources = aiTextOf(run);
        double overlap = similarityGuard.containmentOfAll(myWords, sources);
        if (overlap > COPY_THRESHOLD) {
            throw new ResponseStatusException(UNPROCESSABLE_CONTENT, String.format(
                    "这段有 %.0f%% 是从题干/评分点搬过来的（上限 %.0f%%）。"
                            + "抄一遍不会留下记忆痕迹 —— 合上题目，用你自己的说法重写一遍。",
                    overlap * 100, COPY_THRESHOLD * 100));
        }

        DrillNote note = new DrillNote();
        note.setRunId(runId);
        note.setUserId(userId);
        note.setMyWords(myWords);
        note.setGapFound(emptyToNull(req.gapFound()));
        note.setNextAction(emptyToNull(req.nextAction()));
        note.setOverlapRatio(BigDecimal.valueOf(overlap).setScale(3, RoundingMode.HALF_UP));
        try {
            note = noteRepo.save(note);
        } catch (DataIntegrityViolationException e) {
            // uni_drill_note_run 并发兜底
            throw new ResponseStatusException(CONFLICT, "这道题已经写过内化笔记了");
        }

        return new NoteView(runId, note.getId(),
                note.getOverlapRatio().doubleValue(), debt(userId).size());
    }

    // ----------------------------------------------------------- 债务清单

    /** 答错且未复盘的作答清单（仅展示，不拦截开新题） */
    @Transactional(readOnly = true)
    public List<DebtView> debt(Long userId) {
        return runRepo.findNoteDebt(userId, GradeScale.PASS_LINE).stream()
                .map(r -> new DebtView(r.getRunId(), r.getStem(),
                        r.getRawScore() == null ? 0d : r.getRawScore().doubleValue(),
                        r.getAnsweredAt()))
                .toList();
    }

    // ----------------------------------------------------------- 内部

    /**
     * 收集"AI 产出的文本"作为抄写检测的对照集：题干 + 评分点原文。
     * REHEARSAL 要把每一轮追问都算进去，否则抄追问的题干就检测不到。
     *
     * <p>注意<b>不包含</b>判分里的 evidence：那是从用户自己的答案里摘出来的原话，
     * 拿它当抄袭源会把"我说过的话"判成抄袭，属于误伤。
     */
    private List<String> aiTextOf(DrillRun run) {
        List<String> texts = new ArrayList<>();
        if (run.getMode() == DrillMode.REHEARSAL) {
            for (DrillTurn t : turnRepo.findByRunIdOrderByRoundAsc(run.getId())) {
                texts.add(t.getStem());
                texts.addAll(pointTexts(t.getPointsJson()));
            }
        }
        qbRepo.findById(run.getQuestionId()).ifPresent(q -> {
            texts.add(q.getStem());
            texts.addAll(pointTexts(q.getPointsJson()));
        });
        texts.removeIf(s -> s == null || s.isBlank());
        return texts;
    }

    private List<String> pointTexts(String pointsJson) {
        if (pointsJson == null || pointsJson.isBlank()) {
            return List.of();
        }
        try {
            GeneratedQuestion gq = objectMapper.readValue(pointsJson, GeneratedQuestion.class);
            return gq.allPoints().stream().map(GeneratedQuestion.Point::text).toList();
        } catch (Exception e) {
            log.warn("评分点反序列化失败，抄写检测将只比对题干 runPoints={}", pointsJson, e);
            return List.of();
        }
    }

    private String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private String emptyToNull(String s) {
        String t = trim(s);
        return t.isEmpty() ? null : t;
    }
}
