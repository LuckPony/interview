package interview.homegrown.modules.drill.repository;

import interview.homegrown.modules.drill.domain.DrillRun;
import interview.homegrown.modules.drill.domain.DrillRunStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DrillRunRepository extends JpaRepository<DrillRun, Long> {

    // 物理闸门读取：同一用户未闭环的作答（READY/ANSWERING）
    List<DrillRun> findByUserIdAndStatusIn(Long userId, List<DrillRunStatus> statuses);

    Optional<DrillRun> findByUserIdAndId(Long userId, Long id);

    /**
     * 内化债务：已判分、分数没过线、却还没写笔记的作答。
     *
     * <p>这是痛点 7 的闸门数据源。刻意<b>只统计没过线的</b>：全对的题不写笔记不算欠账，
     * 逼着人给自己已经会的东西写反思，只会把这个机制变成又一个走形式的打卡。
     *
     * <p>三张表在 HQL 里用 theta join（逗号 + where 关联）而不是 JPA 关联：
     * drill_run / grade_result / question_bank 之间刻意没建实体关联，
     * 避免 JPA 把它们拖成一张对象图 —— 这几张表的生命周期与一致性要求完全不同。
     *
     * <p>返回投影而非实体：一次查询把 stem 和分数都带回来，否则调用方要为每条欠账
     * 再查一次 grade_result，典型 N+1。
     */
    @Query("""
            select r.id as runId, q.stem as stem, g.rawScore as rawScore, r.updatedAt as answeredAt
            from DrillRun r, GradeResult g, QuestionBank q
            where g.runId = r.id
              and q.id = r.questionId
              and r.userId = :userId
              and g.rawScore < :passLine
              and not exists (select n.id from DrillNote n where n.runId = r.id)
            order by r.id asc
            """)
    List<NoteDebtRow> findNoteDebt(@Param("userId") Long userId, @Param("passLine") BigDecimal passLine);

    /** 内化债务投影。别名必须与 HQL 的 as 一致，否则 Spring Data 映射不上。 */
    interface NoteDebtRow {
        Long getRunId();

        String getStem();

        BigDecimal getRawScore();

        Instant getAnsweredAt();
    }

    /**
     * 问答记录：当前用户已判分的 LEARN 作答，按判分时间倒排。
     * 关联 grade_result 拿分数/档位/判分时间，left join drill_note 判断是否有笔记。
     */
    @Query("""
            select r.id as runId, q.stem as stem, g.rawScore as rawScore, g.grade as grade,
                   g.createdAt as answeredAt, n.id as noteId
            from DrillRun r
            join GradeResult g on g.runId = r.id
            join QuestionBank q on q.id = r.questionId
            left join DrillNote n on n.runId = r.id
            where r.userId = :userId
              and r.status = :graded
            order by g.createdAt desc
            """)
    List<HistoryRow> findHistory(@Param("userId") Long userId, @Param("graded") DrillRunStatus graded);

    interface HistoryRow {
        Long getRunId();

        String getStem();

        BigDecimal getRawScore();

        interview.homegrown.modules.drill.domain.Grade getGrade();

        Instant getAnsweredAt();

        Long getNoteId();
    }
}
