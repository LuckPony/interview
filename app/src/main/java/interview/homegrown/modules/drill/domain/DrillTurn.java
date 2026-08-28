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

    /**
     * 教学讲解：判分落地后由 TutorGenerator 写一段老师式讲解
     * （基于 stem + 评分点 + 判分 + 学生答案，帮用户理解这道题，不是判分报告）。
     * null = 暂无（生成失败或尚未生成），前端按 null 不渲染处理。
     */
    @Column(columnDefinition = "text")
    private String tutorText;

    /** 本轮用户消息附带的图片（JSON 数组，data URL 形式；仅视觉模型可用）。 */
    @Column(name = "image_json", columnDefinition = "text")
    private String imageJson;

    // ---- 苏格拉底每轮判定 ----
    /** 本轮 AI 判定状态：answering（还在答）/ needs_guide（答完未达标，需引导）/ done（达标） */
    @Column(name = "judge_state", length = 20)
    private String judgeState;

    /** 评分点覆盖度 0~1（done/needs_guide 时填，answering 为 null） */
    private BigDecimal coverage;

    /** 是否有致命缺漏 */
    @Column(name = "fatal_gap")
    private Boolean fatalGap;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;
}
