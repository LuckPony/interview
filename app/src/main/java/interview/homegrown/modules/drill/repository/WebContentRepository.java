package interview.homegrown.modules.drill.repository;

import interview.homegrown.modules.drill.domain.WebContent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WebContentRepository extends JpaRepository<WebContent, Long> {

    Optional<WebContent> findByConceptId(Long conceptId);

    List<WebContent> findByConceptIdIn(List<Long> conceptIds);

    void deleteByConceptId(Long conceptId);
}
