package interview.homegrown.modules.drill.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 内化笔记（痛点 7 的物理解法）。
 * 【故意】没有 summary / correctAnswer / aiExplanation 字段：
 * 用户在结构上就无法把 AI 给的标准答案粘进来，只能写自己的话、缺口与下一步动作。
 * myWords 还会被服务端做 trigram 重合度校验，抄题干/评分点会被拒收。
 */
@Entity
@Table(name = "drill_note")
@Getter
@Setter
public class DrillNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long runId;

    @Column(nullable = false)
    private Long userId;

    @Column(columnDefinition = "text", nullable = false)
    private String myWords;

    @Column(columnDefinition = "text")
    private String gapFound;

    @Column(columnDefinition = "text")
    private String nextAction;

    private BigDecimal overlapRatio;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;
}
