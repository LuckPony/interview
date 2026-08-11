package interview.homegrown.modules.drill.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * AI 复盘缓存：每个已判分作答一份，含「对话总结+欠缺点 / 解题思路 / 记忆口诀」。
 * 生成一次即缓存，避免反复调 LLM。
 */
@Entity
@Table(name = "drill_review")
@Getter
@Setter
public class DrillReview {

    @Id
    private Long runId;

    @Column(columnDefinition = "text")
    private String gapSummary;

    @Column(columnDefinition = "text")
    private String approach;

    @Column(columnDefinition = "text")
    private String mnemonic;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;
}
