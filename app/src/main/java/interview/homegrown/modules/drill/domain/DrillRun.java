package interview.homegrown.modules.drill.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * 一次作答实例。状态机推进靠 status 字段，并受部分唯一索引物理闸门约束。
 */
@Entity
@Table(name = "drill_run")
@Getter
@Setter
public class DrillRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long questionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DrillMode mode = DrillMode.LEARN;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AnswerMode answerMode = AnswerMode.WRITE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DrillRunStatus status = DrillRunStatus.READY;

    private String timing;          // NONE / COUNTDOWN（opt-in 计时）

    @Column(nullable = false)
    private boolean openBook = false;

    private Integer activeSeconds;  // 有效作答时长（心跳累计）

    /**
     * 答案揭示边界：首次明确索要答案/提示的轮次（drill_turn.round）。
     * null=从未索要，finish 评分拼接全部用户回答；非空=只拼接该轮之前的回答
     * （之后可能是照着答案复述，不计入量化评分）。由 chat 端点在判分前写入。
     */
    private Integer answerRevealedRound;

    @Column(columnDefinition = "text")
    private String transcript;       // SPEAK 预留

    @Column(nullable = false)
    private int currentRound = 0;    // REHEARSAL 当前轮（0=主问）

    @Column(nullable = false)
    private int maxRound = 0;        // REHEARSAL 追问封顶（LEARN 恒为 0）

    /**
     * 追问来源：仅当 mode=REHEARSAL 且本 run 是从某 LEARN run 的 grade 卡通过"继续追问"按钮 spawn 时填入。
     * settle 时检测非空 → 跳过 mastery 应用（追问不算正式面试，不取 L3）。
     * 同一表自引用，加 FK 让孤儿追问自动挂掉。
     */
    private Long sourceRunId;

    /**
     * 「先教后考」本次作答聚焦的子知识点。只有该字段非空的已评分 run 才会让对应子点显示完成；
     * 不再因为概念 mastery 被更新，就把整个大知识点直接显示为完成。
     */
    @Column(name = "focus_sub_point", length = 300)
    private String focusSubPoint;

    // ---------------------------------------------------- 三阶段练习（独立作答 → 讲解 → 迁移测试）
    // 阶段1 submit 判分锁定 first_grade（基础档位），阶段3 迁移测试答对可「降级通过」升级（封顶 GOOD）。

    /** 当前阶段：FIRST_ANSWER（待独立作答）/ TUTORING（讲解中）/ TRANSFER_TEST（迁移测试中）/ DONE（已结束） */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DrillPhase phase = DrillPhase.FIRST_ANSWER;

    /** 阶段1锁定的基础档位（AGAIN/HARD/GOOD/EASY）。null=尚未独立判分。 */
    @Column(name = "first_grade", length = 10)
    private String firstGrade;

    /** 已完成的迁移测试轮数（防无限追问）。 */
    @Column(name = "transfer_count", nullable = false)
    private int transferCount = 0;

    /** 迁移测试轮数上限（默认 2）。 */
    @Column(name = "transfer_max", nullable = false)
    private int transferMax = 2;

    /** 迁移测试题题干（结合已掌握知识点生成，不落 question_bank）。 */
    @Column(name = "transfer_stem", columnDefinition = "text")
    private String transferStem;

    /** 迁移测试题评分点（GeneratedQuestion JSON）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "transfer_points", columnDefinition = "jsonb")
    private String transferPointsJson;

    /** 迁移测试题概念 id 顺序（index 0 = PRIMARY 当前概念）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "transfer_concept_ids", columnDefinition = "jsonb")
    private String transferConceptIdsJson;

    /** 学习工作流用途；综合检测仍复用普通聊天和评分，只额外记录统计口径。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DrillPurpose purpose = DrillPurpose.SUB_POINT_PRACTICE;

    /** 本次练习所属方向；旧自由练习可空。 */
    private Long planId;

    /** 大知识点综合检测目标。 */
    private Long assessmentConceptId;

    /** L1-L5 层级综合检测目标。 */
    private Integer assessmentLayer;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(insertable = false)
    private Instant updatedAt;
}
