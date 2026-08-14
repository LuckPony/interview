package interview.homegrown.modules.drill.service;

import interview.homegrown.modules.drill.domain.DrillRun;
import interview.homegrown.modules.drill.repository.DrillNoteRepository;
import interview.homegrown.modules.drill.repository.DrillReviewRepository;
import interview.homegrown.modules.drill.repository.DrillRunRepository;
import interview.homegrown.modules.drill.repository.DrillTurnRepository;
import interview.homegrown.modules.drill.repository.GradeResultRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * 记录清理：删除问答记录 / 内化复盘数据（用户 2026-08 需求）。
 *
 * <p>删除是<b>级联</b>的：drill_run 被 drill_review（run_id 主键）、drill_note、drill_turn、
 * grade_result 以及 REHEARSAL 追问场（source_run_id）外键引用，且这些外键都没有
 * ON DELETE CASCADE，必须按依赖顺序逐层删除，否则 FK 违例会 500。
 *
 * <p>删除顺序（单条 run）：先递归删掉由它 spawn 的追问场 → 再删复盘/笔记/轮次/判分 → 最后删 run。
 * 若删的是进行中（READY/ANSWERING）的活跃 run，删除后物理闸门（部分唯一索引）自动放行下一题。
 *
 * <p>刻意<b>不动</b>：question_bank（无 user_id 归属，且 daily_task 引用它）与
 * mastery（掌握度）。删除记录是清档案，不该重置学习进度。
 */
@Service
public class RecordCleanupService {

    private final DrillRunRepository runRepo;
    private final DrillTurnRepository turnRepo;
    private final GradeResultRepository gradeRepo;
    private final DrillNoteRepository noteRepo;
    private final DrillReviewRepository reviewRepo;

    public RecordCleanupService(DrillRunRepository runRepo, DrillTurnRepository turnRepo,
                                GradeResultRepository gradeRepo, DrillNoteRepository noteRepo,
                                DrillReviewRepository reviewRepo) {
        this.runRepo = runRepo;
        this.turnRepo = turnRepo;
        this.gradeRepo = gradeRepo;
        this.noteRepo = noteRepo;
        this.reviewRepo = reviewRepo;
    }

    /**
     * 删除一道题（questionId）下当前用户的整条对话线：原答、重答、追问场、判分、复盘、笔记全删。
     * 供「问答记录」页删除整条记录。
     *
     * @return 删除的 run 条数
     */
    @Transactional
    public int deleteConversation(Long userId, Long questionId) {
        List<DrillRun> runs = runRepo.findByUserIdAndQuestionIdOrderByIdAsc(userId, questionId);
        if (runs.isEmpty()) {
            throw new ResponseStatusException(NOT_FOUND, "该题没有问答记录");
        }
        Set<Long> deleted = new HashSet<>();
        for (DrillRun run : runs) {
            deleteRunCascade(run, deleted);
        }
        return deleted.size();
    }

    /**
     * 删除单条作答记录及其全部关联数据（追问场、判分、复盘、笔记）。
     * 供「内化复盘」页删除欠账 / 复盘数据。
     *
     * @return 删除的 run 条数（含追问场）
     */
    @Transactional
    public int deleteRun(Long userId, Long runId) {
        DrillRun run = runRepo.findByUserIdAndId(userId, runId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "记录不存在"));
        Set<Long> deleted = new HashSet<>();
        deleteRunCascade(run, deleted);
        return deleted.size();
    }

    /**
     * 级联删除单个 run：先递归删追问场，再删子表，最后删 run 本身。
     * deleted 集合防重：同一 run 可能同时被「对话线列表」与「source_run_id 递归」覆盖到。
     */
    private void deleteRunCascade(DrillRun run, Set<Long> deleted) {
        if (deleted.contains(run.getId())) return;
        // 1) 由本 run spawn 的 REHEARSAL 追问场（source_run_id 指向本 run），递归处理
        for (DrillRun followup : runRepo.findBySourceRunId(run.getId())) {
            deleteRunCascade(followup, deleted);
        }
        // 2) 子表（外键指向 drill_run，先删）
        reviewRepo.deleteByRunId(run.getId());
        noteRepo.deleteByRunId(run.getId());
        turnRepo.deleteByRunId(run.getId());
        gradeRepo.deleteByRunId(run.getId());
        // 3) run 本身
        runRepo.delete(run);
        deleted.add(run.getId());
    }
}
