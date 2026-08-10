package interview.homegrown.modules.drill.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 掌握度。深度画像由 GROUP BY topic, MAX(layer) 直出，不建 Elo（layer 本身就是难度刻度）。
 * mastery_level 0-3（3=模拟面试达标）。
 */
@Entity
@Table(name = "mastery")
@Getter
@Setter
public class Mastery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long conceptId;

    @Column(nullable = false)
    private int masteryLevel = 0;

    @Enumerated(EnumType.STRING)
    private Grade lastGrade;

    private Instant dueAt;          // 下次复习时间（FSRS 排程）

    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    @Column(insertable = false, updatable = false)
    private Instant updatedAt;
}
