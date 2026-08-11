package interview.homegrown.modules.drill.repository;

import interview.homegrown.modules.drill.domain.StudyPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface StudyPlanRepository extends JpaRepository<StudyPlan, Long> {

    List<StudyPlan> findByUserId(Long userId);

    Optional<StudyPlan> findByUserIdAndTitle(Long userId, String title);

    /** 定时任务用：列出所有有学习方向的用户 id */
    @Query("select distinct p.userId from StudyPlan p")
    List<Long> findDistinctUserIds();
}
