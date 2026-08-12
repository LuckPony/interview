package interview.homegrown.modules.drill.service;

import interview.homegrown.modules.drill.domain.Concept;
import interview.homegrown.modules.drill.domain.DrillMode;
import interview.homegrown.modules.drill.domain.DrillNote;
import interview.homegrown.modules.drill.domain.DrillRun;
import interview.homegrown.modules.drill.domain.DrillRunStatus;
import interview.homegrown.modules.drill.domain.DrillTurn;
import interview.homegrown.modules.drill.domain.GradeResult;
import interview.homegrown.modules.drill.domain.QuestionBank;
import interview.homegrown.modules.drill.repository.ConceptRepository;
import interview.homegrown.modules.drill.repository.DrillNoteRepository;
import interview.homegrown.modules.drill.repository.DrillRunRepository;
import interview.homegrown.modules.drill.repository.DrillTurnRepository;
import interview.homegrown.modules.drill.repository.GradeResultRepository;
import interview.homegrown.modules.drill.repository.QuestionBankRepository;
import interview.homegrown.modules.drill.web.dto.ConversationView;
import interview.homegrown.modules.drill.web.dto.RunDetailView;
import interview.homegrown.modules.drill.web.dto.RunSummaryView;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * 问答记录查询：按题（questionId）聚合成一条对话线。
 *
 * <p>模型语义（用户 2026-08-10 决策）：
 * <b>一条记录 = 一道题的一次完整对话</b>。同一道题的 LEARN 原答、restart 重答、
 * REHEARSAL 追问场（sourceRunId 关联）全部归入同一条对话线，按时间顺序展示；
 * 列表层不再按 run 一行一条，而是按 questionId 一行一条。
 */
@Service
public class HistoryService {

    private final DrillRunRepository runRepo;
    private final GradeResultRepository gradeRepo;
    private final QuestionBankRepository qbRepo;
    private final DrillNoteRepository noteRepo;
    private final DrillTurnRepository turnRepo;
    private final ConceptRepository conceptRepo;

    public HistoryService(DrillRunRepository runRepo, GradeResultRepository gradeRepo,
                          QuestionBankRepository qbRepo, DrillNoteRepository noteRepo,
                          DrillTurnRepository turnRepo, ConceptRepository conceptRepo) {
        this.runRepo = runRepo;
        this.gradeRepo = gradeRepo;
        this.qbRepo = qbRepo;
        this.noteRepo = noteRepo;
        this.turnRepo = turnRepo;
        this.conceptRepo = conceptRepo;
    }

    /** 列表按 questionId 聚合：一行一道题，展示最近一次 run 的状态 + 该题练过几轮 */
    public List<RunSummaryView> list(Long userId) {
        // 查所有 LEARN run（含 GRADED + 进行中 READY/ANSWERING），按 id 倒序
        List<DrillRun> allLearns = runRepo.findByUserIdAndModeOrderByIdDesc(userId, DrillMode.LEARN);

        // 按 questionId 分组
        Map<Long, List<DrillRun>> byQuestion = new LinkedHashMap<>();
        for (DrillRun run : allLearns) {
            byQuestion.computeIfAbsent(run.getQuestionId(), k -> new ArrayList<>()).add(run);
        }

        List<RunSummaryView> views = new ArrayList<>();
        for (Map.Entry<Long, List<DrillRun>> e : byQuestion.entrySet()) {
            List<DrillRun> runs = e.getValue();
            DrillRun latest = runs.get(0);  // id 倒序，第一个 = 最新

            QuestionBank q = qbRepo.findById(latest.getQuestionId()).orElse(null);
            if (q == null) continue;

            // GRADED 才有 GradeResult；进行中的 run 没有
            GradeResult gr = null;
            if (latest.getStatus() == DrillRunStatus.GRADED) {
                gr = gradeRepo.findByRunId(latest.getId()).orElse(null);
            }

            boolean hasNote = runs.stream().anyMatch(r -> noteRepo.findByRunId(r.getId()).isPresent());

            views.add(new RunSummaryView(
                    latest.getId(),
                    q.getStem(),
                    gr == null || gr.getRawScore() == null ? 0 : gr.getRawScore().doubleValue(),
                    gr == null || gr.getGrade() == null ? null : gr.getGrade().name(),
                    latest.getUpdatedAt() != null ? latest.getUpdatedAt() : latest.getCreatedAt(),
                    hasNote,
                    latest.getQuestionId(),
                    runs.size(),
                    latest.getStatus().name(),
                    planIdOf(latest.getQuestionId())
            ));
        }
        return views;
    }

    /** 某道题的主概念所属学习方向（供按方向过滤问答记录）。 */
    private Long planIdOf(Long questionId) {
        QuestionBank q = qbRepo.findById(questionId).orElse(null);
        if (q == null || q.getConceptIds() == null || q.getConceptIds().length == 0) return null;
        return conceptRepo.findById(q.getConceptIds()[0].longValue())
                .map(Concept::getStudyPlanId)
                .orElse(null);
    }

    /**
     * 完整对话线：该 questionId 下所有 LEARN run（含进行中 READY/ANSWERING + 已判分 GRADED）按时间升序，
     * 每个 run 带全部轮次问答与判分。前端点开卡片后渲染这段历史，可回看每轮问答与判分，
     * 并在底部「继续对话」（恢复进行中 run）或「重练此题」（基于已判分 run 开新 run）。
     */
    public ConversationView conversation(Long userId, Long questionId) {
        QuestionBank q = qbRepo.findById(questionId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "题目已失效"));

        // 全状态查询：包含 GRADED + READY + ANSWERING
        List<DrillRun> runs = runRepo.findByUserIdAndQuestionIdOrderByIdAsc(userId, questionId);
        if (runs.isEmpty()) {
            throw new ResponseStatusException(NOT_FOUND, "该题还没有作答记录");
        }

        List<ConversationView.ConversationRunView> runViews = new ArrayList<>();
        for (DrillRun run : runs) {
            GradeResult gr = gradeRepo.findByRunId(run.getId()).orElse(null);
            List<ConversationView.ConversationTurnView> turnViews = turnRepo
                    .findByRunIdOrderByRoundAsc(run.getId()).stream()
                    .map(t -> new ConversationView.ConversationTurnView(
                            t.getRound(),
                            t.getStem(),
                            t.getRawAnswer(),
                            t.getRawScore() == null ? 0 : t.getRawScore().doubleValue(),
                            t.getPassed(),
                            t.getByConceptJson(),
                            t.getTutorText()))
                    .toList();
            runViews.add(new ConversationView.ConversationRunView(
                    run.getId(),
                    run.getMode().name(),
                    run.getStatus().name(),
                    run.getSourceRunId(),
                    gr == null || gr.getRawScore() == null ? 0 : gr.getRawScore().doubleValue(),
                    gr == null || gr.getGrade() == null ? null : gr.getGrade().name(),
                    run.getCreatedAt(),
                    turnViews
            ));
        }

        return new ConversationView(questionId, q.getStem(), q.getProbeType().name(), q.getResponseFormat().name(), runViews);
    }

    /** 单条 run 详情（旧入口，保留给历史逻辑）：仅当无对话线视图时兜底 */
    public RunDetailView detail(Long userId, Long runId) {
        DrillRun run = runRepo.findByUserIdAndId(userId, runId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "记录不存在"));
        if (run.getStatus() != DrillRunStatus.GRADED) {
            throw new ResponseStatusException(NOT_FOUND, "记录尚未判分");
        }

        GradeResult gr = gradeRepo.findByRunId(runId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "判分结果不存在"));
        QuestionBank q = qbRepo.findById(run.getQuestionId())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "题目已失效"));
        Optional<DrillNote> note = noteRepo.findByRunId(runId);

        String rawAnswer = turnRepo.findByRunIdOrderByRoundAsc(runId).stream()
                .findFirst()
                .map(DrillTurn::getRawAnswer)
                .orElse(null);

        Long[] conceptIds = new Long[0];
        Integer[] raw = q.getConceptIds();
        if (raw != null) {
            conceptIds = new Long[raw.length];
            for (int i = 0; i < raw.length; i++) {
                conceptIds[i] = raw[i].longValue();
            }
        }

        return new RunDetailView(
                runId,
                q.getId(),
                q.getStem(),
                q.getProbeType().name(),
                q.getResponseFormat().name(),
                rawAnswer,
                gr.getRawScore() == null ? 0 : gr.getRawScore().doubleValue(),
                gr.getGrade() == null ? null : gr.getGrade().name(),
                gr.getByConceptJson(),
                gr.getCreatedAt(),
                note.isPresent(),
                conceptIds
        );
    }
}
