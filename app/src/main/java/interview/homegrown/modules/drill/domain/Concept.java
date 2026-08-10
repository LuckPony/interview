package interview.homegrown.modules.drill.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 知识矩阵的一格：topic（行） × layer（列，1-5）。
 * 约 100 格。信息茧房破除靠 layer 分层暴露 L3-L5，不靠依赖边。
 */
@Entity
@Table(name = "concept")
@Getter
@Setter
public class Concept {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String topic;

    @Column(nullable = false)
    private int layer;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    /** 所属学习方向（痛点1：对话生成的学习规划）。可空：旧种子概念不强制归属某方向。 */
    @Column(name = "study_plan_id")
    private Long studyPlanId;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    @Column(insertable = false, updatable = false)
    private Instant updatedAt;
}
