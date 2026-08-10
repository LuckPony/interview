package interview.homegrown.modules.drill.service;

import interview.homegrown.modules.drill.domain.DrillNote;
import interview.homegrown.modules.drill.domain.DrillRun;
import interview.homegrown.modules.drill.domain.DrillRunStatus;
import interview.homegrown.modules.drill.domain.GradeResult;
import interview.homegrown.modules.drill.domain.QuestionBank;
import interview.homegrown.modules.drill.repository.DrillNoteRepository;
import interview.homegrown.modules.drill.repository.DrillRunRepository;
import interview.homegrown.modules.drill.repository.DrillTurnRepository;
import interview.homegrown.modules.drill.repository.GradeResultRepository;
import interview.homegrown.modules.drill.repository.QuestionBankRepository;
import interview.homegrown.modules.drill.web.dto.RunDetailView;
import interview.homegrown.modules.drill.web.dto.RunSummaryView;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * 问答记录查询：已判分作答的列表与详情。
 */
@Service
public class HistoryService {

    private final DrillRunRepository runRepo;
    private final GradeResultRepository gradeRepo;
    private final QuestionBankRepository qbRepo;
    private final DrillNoteRepository noteRepo;
    private final DrillTurnRepository turnRepo;

    public HistoryService(DrillRunRepository runRepo, GradeResultRepository gradeRepo,
                          QuestionBankRepository qbRepo, DrillNoteRepository noteRepo,
                          DrillTurnRepository turnRepo) {
        this.runRepo = runRepo;
        this.gradeRepo = gradeRepo;
        this.qbRepo = qbRepo;
        this.noteRepo = noteRepo;
        this.turnRepo = turnRepo;
    }

    public List<RunSummaryView> list(Long userId) {
        return runRepo.findHistory(userId, DrillRunStatus.GRADED).stream()
                .map(r -> new RunSummaryView(
                        r.getRunId(),
                        r.getStem(),
                        r.getRawScore() == null ? 0 : r.getRawScore().doubleValue(),
                        r.getGrade() == null ? null : r.getGrade().name(),
                        r.getAnsweredAt(),
                        r.getNoteId() != null))
                .toList();
    }

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

        // LEARN 模式从 drill_turn 取用户原答案（V3 后新增落库）；
        // 旧数据无 turn，则 rawAnswer 为空，不影响查看题目与判分。
        String rawAnswer = turnRepo.findByRunIdOrderByRoundAsc(runId).stream()
                .findFirst()
                .map(t -> t.getRawAnswer())
                .orElse(null);

        // 关联概念：question_bank.concept_ids[]，供前端"继续追问"接力 REHEARSAL 取锚点（[0]）
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
