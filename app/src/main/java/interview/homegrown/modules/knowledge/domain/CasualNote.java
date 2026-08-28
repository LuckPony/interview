package interview.homegrown.modules.knowledge.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "casual_note")
public class CasualNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(columnDefinition = "text", nullable = false)
    private String content;

    @Column(name = "concept_id")
    private Long conceptId;

    @Column(name = "concept_name", length = 300)
    private String conceptName;

    @Column(name = "chat_id")
    private Long chatId;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;
}
