package interview.homegrown.modules.drill.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 子知识点讲解页的答疑消息（用户私有）。
 *
 * <p>与 {@code drill_turn} 完全解耦：答疑只发生在讲解页、只为解决当前子知识点的疑惑，
 * 不判分、不进 run、不动 mastery、不反哺讲解正文。role 为 'user'（学生提问，anchor 可
 * 为学生选中的讲解片段）或 'assistant'（AI 流式回答落库）。
 */
@Entity
@Table(name = "lesson_qa_message")
public class LessonQaMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "concept_id", nullable = false)
    private Long conceptId;

    @Column(name = "sub_point", nullable = false, length = 300)
    private String subPoint;

    /** user / assistant */
    @Column(nullable = false, length = 10)
    private String role;

    @Column(columnDefinition = "text", nullable = false)
    private String text;

    /** 学生选中的讲解片段，作为这次提问的上下文锚点（可空）。 */
    @Column(columnDefinition = "text")
    private String anchor;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getConceptId() { return conceptId; }
    public void setConceptId(Long conceptId) { this.conceptId = conceptId; }
    public String getSubPoint() { return subPoint; }
    public void setSubPoint(String subPoint) { this.subPoint = subPoint; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getAnchor() { return anchor; }
    public void setAnchor(String anchor) { this.anchor = anchor; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}