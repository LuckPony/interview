package interview.homegrown.modules.interview.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "interview_answer")
public  class InterviewAnswerEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @JoinColumn(name = "session_id", nullable = false)
    private String sessionId;

    @Column(name = "question_index", nullable = false)
    private Integer questionIndex;

    @Column(name = "question_text", nullable = false, length = Integer.MAX_VALUE)
    private String questionText;

    @Column(name = "answer_text", length = Integer.MAX_VALUE)
    private String answerText;

    @Column(name = "score")
    private Integer score;

    @Column(name = "feedback", length = Integer.MAX_VALUE)
    private String feedback;

    @ColumnDefault("false")
    @Column(name = "is_follow_up", nullable = false)
    private Boolean isFollowUp;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void onCreat(){
        this.createdAt = LocalDateTime.now();
    }


}