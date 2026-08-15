package interview.homegrown.modules.drill.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 资料的逻辑主题块：服务端按标题/段落边界启发式切块（原文零改动），
 * LLM 只负责标注 title / topic（对应知识点名）/ summary。
 */
@Entity
@Table(name = "corpus_chunk")
@Getter
@Setter
public class CorpusChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "corpus_id", nullable = false)
    private Long corpusId;

    @Column(nullable = false)
    private int seq;

    @Column(nullable = false)
    private String title;

    /** 该块对应的知识点名（LLM 标注；用于候选知识点清单 & 概念匹配） */
    private String topic;

    private String summary;

    @Column(columnDefinition = "text", nullable = false)
    private String text;

    @Column(name = "char_count", nullable = false)
    private int charCount;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;
}
