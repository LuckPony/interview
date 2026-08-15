package interview.homegrown.modules.drill.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 知识点（concept）的互联网补充内容：建计划时默认对每个知识点 web_search 预取一次，
 * 作为「资料之外的素材」随上下文注入（破除信息茧房）。每知识点一条。
 */
@Entity
@Table(name = "web_content")
@Getter
@Setter
public class WebContent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "concept_id", nullable = false)
    private Long conceptId;

    private String url;

    private String title;

    @Column(columnDefinition = "text", nullable = false)
    private String text;

    @Column(name = "char_count", nullable = false)
    private int charCount;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;
}
