package interview.homegrown.modules.drill.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * REHEARSAL 模式的一轮问答。round 0 = 主问，1/2 = 追问（封顶 2 轮，防无限递归）。
 * 每轮独立判分，全部轮 content 通过才发 mastery L3。
 */
@Entity
@Table(name = "drill_turn")
@Getter
@Setter
public class DrillTurn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long runId;

    @Column(nullable = false)
    private int round;

    @Column(columnDefinition = "text", nullable = false)
    private String stem;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "points", columnDefinition = "jsonb")
    private String pointsJson;

    @Column(columnDefinition = "text")
    private String rawAnswer;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "by_concept", columnDefinition = "jsonb")
    private String byConceptJson;

    private BigDecimal rawScore;

    private Boolean passed;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;
}
