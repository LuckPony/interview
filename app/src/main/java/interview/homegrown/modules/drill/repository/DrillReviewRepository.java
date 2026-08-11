package interview.homegrown.modules.drill.repository;

import interview.homegrown.modules.drill.domain.DrillReview;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DrillReviewRepository extends JpaRepository<DrillReview, Long> {
}
