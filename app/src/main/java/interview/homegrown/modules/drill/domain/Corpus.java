package interview.homegrown.modules.drill.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 用户上传的个人资料（一本书 / 一份项目文档）。
 * Tika 提取文本，章节索引用于规划与出题；新版单文件导入同时保存原文件 key。
 * 图片/扫描件的视觉解析不在本模块处理。
 */
@Entity
@Table(name = "corpus")
@Getter
@Setter
public class Corpus {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String sourceType = "UPLOAD";

    @Column(columnDefinition = "text", nullable = false)
    private String text;

    @Column(nullable = false)
    private int charCount;

    @Column(columnDefinition = "text")
    private String overview;

    @Column(length = 20)
    private String indexState = "PENDING";

    private String originalKey;

    private String originalType;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;
}
