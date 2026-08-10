package interview.homegrown.modules.drill.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 预生成题库。四维正交签名：(concept_ids[], probe_type, answer_mode, response_format)。
 * arity = concept_ids.length，是字段不是类别（单点=1，组合=2-3）。
 * points / mcq_options 以 JSON 文本存储，序列化在 service 层用 Jackson 处理。
 */
@Entity
@Table(name = "question_bank")
@Getter
@Setter
public class QuestionBank {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "concept_ids", columnDefinition = "integer[]", nullable = false)
    private Integer[] conceptIds;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProbeType probeType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AnswerMode answerMode = AnswerMode.WRITE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ResponseFormat responseFormat;

    @Column(nullable = false)
    private int arity = 1;

    @Column(columnDefinition = "text", nullable = false)
    private String stem;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "points", columnDefinition = "jsonb")
    private String pointsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "mcq_options", columnDefinition = "jsonb")
    private String mcqOptionsJson;

    private String codeRef;

    @Column(nullable = false)
    private int usedCount = 0;
}
