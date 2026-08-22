package interview.homegrown.modules.knowledge.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "knowledge_card")
public class KnowledgeCard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CardSource source = CardSource.CHAT;

    @Column(columnDefinition = "text", nullable = false)
    private String question;

    @Column(columnDefinition = "text")
    private String answer;

    /** 逗号分隔标签 */
    private String tags;

    /** AI 当时回复的完整内容（Markdown 原文）；answer 是提炼后的精简答案，detail 是完整记录 */
    @Column(columnDefinition = "text")
    private String detail;

    /** 可选关联概念（LLM 判断相关才打，不强制） */
    @Column(name = "concept_id")
    private Long conceptId;

    /** 可选关联学习方向 */
    @Column(name = "plan_id")
    private Long planId;

    @Column(name = "due_at")
    private Instant dueAt;

    @Column(name = "review_count", nullable = false)
    private int reviewCount;

    @Column(name = "last_reviewed_at")
    private Instant lastReviewedAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;
}