package interview.homegrown.modules.drill.repository;

import interview.homegrown.modules.drill.domain.Concept;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConceptRepository extends JpaRepository<Concept, Long> {
    List<Concept> findByTopic(String topic);
    List<Concept> findByLayer(int layer);
    List<Concept> findByStudyPlanId(Long studyPlanId);
    Optional<Concept> findByStudyPlanIdAndName(Long studyPlanId, String name);
}
