package interview.homegrown.modules.interview.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 面试题目持久化（替代进程内缓存）。
 * 面试出题后落库，取题/评估从数据库读取 —— 后端进程重启不丢，桌面端可随时恢复面试。
 * 面试完成评估后删除。
 */
@Getter
@Setter
@Entity
@Table(name = "interview_question")
public class InterviewQuestionEntity {

    @Id
    @Column(name = "session_id", nullable = false, length = 36)
    private String sessionId;

    /** 题目列表 JSON（InterviewQuestionResult 序列化） */
    @Column(name = "questions_json", nullable = false, length = Integer.MAX_VALUE)
    private String questionsJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
