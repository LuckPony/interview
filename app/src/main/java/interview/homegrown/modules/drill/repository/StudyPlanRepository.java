package interview.homegrown.modules.drill.repository;

import interview.homegrown.modules.drill.domain.StudyPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StudyPlanRepository extends JpaRepository<StudyPlan, Long> {

    List<StudyPlan> findByUserId(Long userId);

    Optional<StudyPlan> findByUserIdAndTitle(Long userId, String title);
}
