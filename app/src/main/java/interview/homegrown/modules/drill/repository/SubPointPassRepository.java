package interview.homegrown.modules.drill.repository;

import interview.homegrown.modules.drill.domain.SubPointPass;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SubPointPassRepository extends JpaRepository<SubPointPass, Long> {

    List<SubPointPass> findByUserId(Long userId);

    Optional<SubPointPass> findByUserIdAndConceptIdAndSubPoint(Long userId, Long conceptId, String subPoint);

    void deleteByUserIdAndConceptIdAndSubPoint(Long userId, Long conceptId, String subPoint);
}
