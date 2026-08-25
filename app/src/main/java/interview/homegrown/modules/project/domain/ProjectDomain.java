package interview.homegrown.modules.project.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 项目分析出的一个业务域（对应一个学习知识点）。
 * 如「对话式辅导」「判分引擎」「教学拆解」，每个域包含若干子知识点。
 * 用户确认后，域会映射为 concept，子知识点映射为 sub_point。
 */
@Entity
@Table(name = "project_domain")
@Getter
@Setter
public class ProjectDomain {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(columnDefinition = "text")
    private String overview;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /** 该域涉及的关键文件路径（JSON 数组字符串）。 */
    @Column(name = "ref_files", columnDefinition = "text")
    private String refFiles;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;
}