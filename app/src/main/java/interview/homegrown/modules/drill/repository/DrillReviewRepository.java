package interview.homegrown.modules.drill.repository;

import interview.homegrown.modules.drill.domain.DrillReview;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DrillReviewRepository extends JpaRepository<DrillReview, Long> {

    /** 删除某 run 的 AI 复盘缓存（记录清理时级联删除） */
    void deleteByRunId(Long runId);
}
