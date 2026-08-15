package interview.homegrown.modules.drill.repository;

import interview.homegrown.modules.drill.domain.DrillTurn;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DrillTurnRepository extends JpaRepository<DrillTurn, Long> {

    List<DrillTurn> findByRunIdOrderByRoundAsc(Long runId);

    Optional<DrillTurn> findByRunIdAndRound(Long runId, int round);

    /** 删除某 run 的全部对话轮次（记录清理时级联删除） */
    void deleteByRunId(Long runId);
}
