package interview.homegrown.modules.drill.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 每日学习任务：每个学习方向每天「复习(REVIEW) + 新学(NEW)」的自动排期。
 *
 * <p>由 {@code DailyPlanService} 生成并异步预出题：status PENDING → READY（question_id 就位），
 * 用户在「今日任务」点开即以预生成题开 run，不等待 LLM。
 */
@Entity
@Table(name = "daily_task")
@Getter
@Setter
public class DailyTask {

    public static final String KIND_REVIEW = "REVIEW";
    public static final String KIND_NEW = "NEW";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_READY = "READY";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_SKIPPED = "SKIPPED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    /** 所属学习方向；null = 全局任务 */
    private Long planId;

    @Column(nullable = false)
    private LocalDate taskDate;

    @Column(nullable = false)
    private String kind;

    @Column(nullable = false)
    private Long conceptId;

    /** 复习任务聚焦的子知识点（null = 概念级复习）；出题/开 run 时限定到该子点 */
    private String subPoint;

    @Column(nullable = false)
    private String status = STATUS_PENDING;

    private Long questionId;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(insertable = false)
    private Instant updatedAt;
}
