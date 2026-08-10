package interview.homegrown.modules.drill.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 用户上传的个人资料（一本书 / 一份项目文档）。
 * v1 只存解析出的纯文本（text），由 Tika 抽取；图片/扫描件的视觉解析本期不做。
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

    @Column(insertable = false, updatable = false)
    private Instant createdAt;
}
