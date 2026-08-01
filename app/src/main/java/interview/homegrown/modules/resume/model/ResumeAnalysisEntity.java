package interview.homegrown.modules.resume.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "resume_analysis")
public class ResumeAnalysisEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @NotNull
    @Column(name = "resume_id", nullable = false)
    private Long resumeId;

    @Column(name = "raw_json", columnDefinition = "text")
    private String rawJson;

    @Column(name = "overall_score")
    private Integer overallScore;

    @Column(name = "summary", columnDefinition = "text")
    private String summary;

    //逗号分隔的优势列表
    @Column(name = "strengths", length = Integer.MAX_VALUE)
    private String strengths;

    //逗号分隔的不足列表
    @Column(name = "weaknesses", length = Integer.MAX_VALUE)
    private String weaknesses;

    //逗号分隔的建议列表
    @Column(name = "suggestions", length = Integer.MAX_VALUE)
    private String suggestions;

    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void onCreate(){
        this.createdAt = LocalDateTime.now();
    }


}