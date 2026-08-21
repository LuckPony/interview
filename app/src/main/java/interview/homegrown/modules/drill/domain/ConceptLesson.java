package interview.homegrown.modules.drill.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 先教后考：某个 concept 下单个子知识点的讲解缓存。
 * 按 (concept_id, sub_point) 唯一 —— 同一子点重复练直接读缓存，不同子点各有各的讲解，
 * 避免一个宽概念（如「Python 基础语法」）只被讲成一个点。
 */
@Entity
@Table(name = "concept_lesson")
@Getter
@Setter
public class ConceptLesson {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "concept_id", nullable = false)
    private Long conceptId;

    @Column(name = "sub_point", nullable = false, length = 300)
    private String subPoint;

    @Column(name = "lesson_text", columnDefinition = "text", nullable = false)
    private String lessonText;

    @Column(name = "char_count", nullable = false)
    private int charCount;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;
}
