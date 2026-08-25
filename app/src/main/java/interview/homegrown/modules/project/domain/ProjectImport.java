package interview.homegrown.modules.project.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * 用户导入的项目，用于自动分析业务域并生成学习计划。
 * 每个项目由用户上传 zip 或提供本地路径触发导入。
 * 分析的业务域与子知识点存于 project_domain / project_sub_point。
 */
@Entity
@Table(name = "project_import")
@Getter
@Setter
public class ProjectImport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 200)
    private String name;

    /** 检测到的技术栈（JSON 数组字符串，如 ["Spring Boot", "React", "PostgreSQL"]）。 */
    @Column(name = "tech_stack", columnDefinition = "text")
    private String techStack;

    /** 导入后项目所在路径（zip 解压到临时目录，或本地路径直指）。 */
    @Column(name = "root_path", nullable = false, columnDefinition = "text")
    private String rootPath;

    /** PENDING → ANALYZING → READY / FAILED */
    @Column(nullable = false, length = 20)
    private String status = "PENDING";

    @Column(name = "error_msg", columnDefinition = "text")
    private String errorMsg;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(insertable = false)
    private Instant updatedAt;
}