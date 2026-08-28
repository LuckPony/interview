package interview.homegrown.modules.drill.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 判分结果。by_concept 统一格式：
 * [{conceptId, role, pointResults:[{point, verdict, evidence}], extraCorrect:[], factualErrors:[]}]
 * verdict ∈ HIT / PARTIAL / MISS；evidence 必填且为用户原话片段。
 * raw_score 与 grade 由服务端算，不委托 LLM。
 */
@Entity
@Table(name = "grade_result")
@Getter
@Setter
public class GradeResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long runId;

    @Column(nullable = false)
    private Long questionId;

    private String answerHash;      // 答案指纹，去重用

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "by_concept", columnDefinition = "jsonb", nullable = false)
    private String byConceptJson;

    private BigDecimal rawScore;

    @Enumerated(EnumType.STRING)
    private Grade grade;

    // ---- 苏格拉底两级评分记录 ----
    /** G1 预引导分（用户首次独立答完后判定，诊断用） */
    @Column(name = "pre_grade", length = 10)
    private String preGrade;

    /** 最终分（=G1 达标，或 G2 引导后分；看答案封 AGAIN） */
    @Column(name = "final_grade", length = 10)
    private String finalGrade;

    /** 是否经过苏格拉底引导 */
    @Column(name = "guided", nullable = false)
    private boolean guided = false;

    /** 引导轮数 */
    @Column(name = "guide_rounds", nullable = false)
    private int guideRounds = 0;

    /** 是否看过答案 */
    @Column(name = "revealed", nullable = false)
    private boolean revealed = false;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;
}
