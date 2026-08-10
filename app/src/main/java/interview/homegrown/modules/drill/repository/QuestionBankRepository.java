package interview.homegrown.modules.drill.repository;

import interview.homegrown.modules.drill.domain.QuestionBank;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface QuestionBankRepository extends JpaRepository<QuestionBank, Long> {

    // 某 concept 参与的所有题目（数组包含查询，用原生 SQL 最稳）
    @Query(value = "SELECT * FROM question_bank WHERE :cid = ANY(concept_ids)", nativeQuery = true)
    List<QuestionBank> findByConceptId(@Param("cid") Long conceptId);

    // 去重硬闸之一：查某 concept 在指定 arity 下已用过的 probe_type
    @Query(value = """
            SELECT DISTINCT probe_type FROM question_bank
            WHERE :cid = ANY(concept_ids) AND arity = :arity
            """, nativeQuery = true)
    List<String> findUsedProbeTypes(@Param("cid") Long conceptId, @Param("arity") int arity);

    // 去重软闸 + 兜底硬闸的数据源：该 concept 最近的题干
    @Query(value = """
            SELECT stem FROM question_bank
            WHERE :cid = ANY(concept_ids)
            ORDER BY id DESC LIMIT :limit
            """, nativeQuery = true)
    List<String> findRecentStems(@Param("cid") Long conceptId, @Param("limit") int limit);
}
