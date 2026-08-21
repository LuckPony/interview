package interview.homegrown.modules.drill.repository;

import interview.homegrown.modules.drill.domain.ConceptLesson;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ConceptLessonRepository extends JpaRepository<ConceptLesson, Long> {

    Optional<ConceptLesson> findByConceptIdAndSubPoint(Long conceptId, String subPoint);
}
