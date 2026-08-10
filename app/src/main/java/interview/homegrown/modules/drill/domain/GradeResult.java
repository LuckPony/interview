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

    @Column(insertable = false, updatable = false)
    private Instant createdAt;
}
