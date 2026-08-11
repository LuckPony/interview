package interview.homegrown.modules.drill.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

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

    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    @Column(insertable = false, updatable = false)
    private Instant updatedAt;
}
