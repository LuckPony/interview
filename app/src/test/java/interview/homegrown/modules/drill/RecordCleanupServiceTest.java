package interview.homegrown.modules.drill;

import interview.homegrown.modules.drill.domain.DrillMode;
import interview.homegrown.modules.drill.domain.DrillNote;
import interview.homegrown.modules.drill.domain.DrillReview;
import interview.homegrown.modules.drill.domain.DrillRun;
import interview.homegrown.modules.drill.domain.DrillRunStatus;
import interview.homegrown.modules.drill.domain.DrillTurn;
import interview.homegrown.modules.drill.domain.Grade;
import interview.homegrown.modules.drill.domain.GradeResult;
import interview.homegrown.modules.drill.repository.DrillNoteRepository;
import interview.homegrown.modules.drill.repository.DrillReviewRepository;
import interview.homegrown.modules.drill.repository.DrillRunRepository;
import interview.homegrown.modules.drill.repository.DrillTurnRepository;
import interview.homegrown.modules.drill.repository.GradeResultRepository;
import interview.homegrown.modules.drill.service.RecordCleanupService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 记录清理（删除问答记录 / 内化复盘数据）的级联回归锁：
 * drill_run 被 drill_review / drill_note / drill_turn / grade_result 与
 * REHEARSAL 追问场（source_run_id）外键引用，删除必须按依赖顺序逐层清，且追问场先于本 run。
 */
@SpringBootTest
@ActiveProfiles("test")
class RecordCleanupServiceTest {

    @Autowired
    private RecordCleanupService cleanup;
    @Autowired
    private DrillRunRepository runRepo;
    @Autowired
    private DrillTurnRepository turnRepo;
    @Autowired
    private GradeResultRepository gradeRepo;
    @Autowired
    private DrillNoteRepository noteRepo;
    @Autowired
    private DrillReviewRepository reviewRepo;

    private DrillRun newRun(Long userId, Long questionId, DrillMode mode, DrillRunStatus status) {
        DrillRun r = new DrillRun();
        r.setUserId(userId);
        r.setQuestionId(questionId);
        r.setMode(mode);
        r.setStatus(status);
        return runRepo.save(r);
    }

    /** 给一个 run 挂上轮次 + 判分 +（可选）笔记/复盘，模拟真实作答的完整数据链 */
    private void attach(Long runId, Long questionId, Long userId, boolean withNote, boolean withReview) {
        DrillTurn t = new DrillTurn();
        t.setRunId(runId);
        t.setRound(0);
        t.setStem("如何实现线程安全的计数器？");
        t.setRawAnswer("加锁");
        t.setByConceptJson("[]");
        t.setRawScore(BigDecimal.valueOf(30));
        t.setPassed(false);
        turnRepo.save(t);

        GradeResult g = new GradeResult();
        g.setRunId(runId);
        g.setQuestionId(questionId);
        g.setAnswerHash("hash-" + runId);
        g.setByConceptJson("[]");
        g.setRawScore(BigDecimal.valueOf(30));
        g.setGrade(Grade.AGAIN);
        gradeRepo.save(g);

        if (withNote) {
            DrillNote n = new DrillNote();
            n.setRunId(runId);
            n.setUserId(userId);
            n.setMyWords("我的复述：用 synchronized 保证原子性。");
            noteRepo.save(n);
        }
        if (withReview) {
            DrillReview rv = new DrillReview();
            rv.setRunId(runId);
            rv.setGapSummary("不知道原子性");
            rv.setApproach("先锁后写");
            rv.setMnemonic("锁写读");
            reviewRepo.save(rv);
        }
    }

    @Test
    @DisplayName("删除对话线：该题全部 run（含追问场）及判分/复盘/笔记级联清除")
    @Transactional
    void deleteConversationCascades() {
        Long uid = 42L;
        Long qid = 1001L;
        DrillRun main = newRun(uid, qid, DrillMode.LEARN, DrillRunStatus.GRADED);
        DrillRun followup = newRun(uid, qid, DrillMode.REHEARSAL, DrillRunStatus.GRADED);
        followup.setSourceRunId(main.getId());
        runRepo.save(followup);
        attach(main.getId(), qid, uid, true, true);
        attach(followup.getId(), qid, uid, false, false);

        int deleted = cleanup.deleteConversation(uid, qid);

        assertThat(deleted).isEqualTo(2);
        assertThat(runRepo.findById(main.getId())).isEmpty();
        assertThat(runRepo.findById(followup.getId())).isEmpty();
        assertThat(turnRepo.findByRunIdOrderByRoundAsc(main.getId())).isEmpty();
        assertThat(gradeRepo.findByRunId(main.getId())).isEmpty();
        assertThat(noteRepo.findByRunId(main.getId())).isEmpty();
        assertThat(reviewRepo.findById(main.getId())).isEmpty();
    }

    @Test
    @DisplayName("删除单条 run：其追问场与关联数据一并删除，其他用户数据不受影响")
    @Transactional
    void deleteRunCascadesFollowups() {
        Long uid = 43L;
        Long qid = 1002L;
        DrillRun main = newRun(uid, qid, DrillMode.LEARN, DrillRunStatus.GRADED);
        DrillRun followup = newRun(uid, qid, DrillMode.REHEARSAL, DrillRunStatus.GRADED);
        followup.setSourceRunId(main.getId());
        runRepo.save(followup);
        DrillRun other = newRun(uid, 1003L, DrillMode.LEARN, DrillRunStatus.GRADED);
        attach(main.getId(), qid, uid, true, true);
        attach(other.getId(), 1003L, uid, false, false);

        cleanup.deleteRun(uid, main.getId());

        assertThat(runRepo.findById(main.getId())).isEmpty();
        assertThat(runRepo.findById(followup.getId())).isEmpty();
        assertThat(noteRepo.findByRunId(main.getId())).isEmpty();
        assertThat(reviewRepo.findById(main.getId())).isEmpty();
        // 别的题不受影响
        assertThat(runRepo.findById(other.getId())).isPresent();
        assertThat(turnRepo.findByRunIdOrderByRoundAsc(other.getId())).hasSize(1);
    }
}
