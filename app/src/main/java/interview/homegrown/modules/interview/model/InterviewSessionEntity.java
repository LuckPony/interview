package interview.homegrown.modules.interview.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 面试会话实体
 * 对应数据库 interview_session 表
 */

@Getter
@Setter
@Entity
@Table(name = "interview_session")
public class InterviewSessionEntity {
    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "resume_id")
    private Long resumeId;

    @Column(name = "skill_id", length = 50)
    private String skillId;

   @Enumerated(EnumType.STRING)
    @Column(name = "difficulty", length = 20)
    private InterviewDifficulty difficulty;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InterviewStatus status;

    @Column(name = "total_questions", nullable = false)
    private int totalQuestions;

    @Column(name = "current_question_index", nullable = false)
    private int currentQuestionIndex;

    @Column(name = "total_score")
    private Integer totalScore;

    @Column(name = "evaluation_json", length = Integer.MAX_VALUE)
    private String evaluationJson;

    @Column(name = "llm_provider", length = 50)
    private String llmProvider;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void onCreat(){
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    public void onUpdate(){
        this.updatedAt = LocalDateTime.now();
    }

}