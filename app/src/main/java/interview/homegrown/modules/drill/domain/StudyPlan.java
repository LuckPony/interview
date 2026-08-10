package interview.homegrown.modules.drill.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 学习方向（痛点 1）：用户自建、由多轮对话动态生成的学习规划容器。
 * 一个方向聚合若干 {@link Concept}（经 concept.study_plan_id 关联）。
 * 与全局种子概念解耦：种子概念 study_plan_id 为 null，仍可被「系统帮我选」抽到。
 */
@Entity
@Table(name = "study_plan")
@Getter
@Setter
public class StudyPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "text")
    private String goal;

    @Column(nullable = false)
    private String status = "ACTIVE";

    /** 绑定的个人资料（用户基于某本书 / 项目资料学习）。可空。 */
    @Column(name = "corpus_id")
    private Long corpusId;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    @Column(insertable = false, updatable = false)
    private Instant updatedAt;
}
