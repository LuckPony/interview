package interview.homegrown.modules.drill.repository;

import interview.homegrown.modules.drill.domain.GradeResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GradeResultRepository extends JpaRepository<GradeResult, Long> {

    // 去重兜底：同一题相同答案指纹已判过则直接复用
    Optional<GradeResult> findByQuestionIdAndAnswerHash(Long questionId, String answerHash);

    // 写笔记时要拿到这次的判分留痕（分数决定 gapFound 是否强制填）
    Optional<GradeResult> findByRunId(Long runId);

    /** 取该 run 最新一条判分结果（幂等重建评分用；防御性，防同 run 多条历史遗留） */
    Optional<GradeResult> findFirstByRunIdOrderByIdDesc(Long runId);

    /** 删除某 run 的判分结果（记录清理时级联删除） */
    void deleteByRunId(Long runId);
}
