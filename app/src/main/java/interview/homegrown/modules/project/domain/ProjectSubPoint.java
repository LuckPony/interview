package interview.homegrown.modules.project.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 业务域下的一个子知识点（对应项目内一个关键机制 / 子模块 / 流程）。
 * 描述是「项目专属」的：这个项目在这个点上怎么实现、为什么这么设计、注意什么，
 * 而不是通用概念解释。
 */
@Entity
@Table(name = "project_sub_point")
@Getter
@Setter
public class ProjectSubPoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "domain_id", nullable = false)
    private Long domainId;

    @Column(nullable = false, length = 300)
    private String name;

    /** 项目专属描述（讲解依据、导览展示）。 */
    @Column(columnDefinition = "text")
    private String description;

    /** 涉及的关键文件路径（JSON 数组字符串）。 */
    @Column(name = "ref_files", columnDefinition = "text")
    private String refFiles;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;
}