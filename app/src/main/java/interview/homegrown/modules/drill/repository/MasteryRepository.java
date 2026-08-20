package interview.homegrown.modules.drill.repository;

import interview.homegrown.modules.drill.domain.Mastery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MasteryRepository extends JpaRepository<Mastery, Long> {

    List<Mastery> findByUserId(Long userId);

    Optional<Mastery> findByUserIdAndConceptId(Long userId, Long conceptId);

    // 删除知识点时连带清掉它的掌握度记录（mastery.concept_id 有 FK 到 concept）
    void deleteByConceptId(Long conceptId);

    // upsert：首次插入，重复则覆盖（深度画像与排程都靠它）
    @Modifying
    @Query(value = """
            MERGE INTO mastery (user_id, concept_id, mastery_level, last_grade, due_at, updated_at)
            KEY(user_id, concept_id)
            VALUES (:uid, :cid, :lvl, :grade, :due, now())
            """, nativeQuery = true)
    void upsert(@Param("uid") Long userId,
                @Param("cid") Long conceptId,
                @Param("lvl") int level,
                @Param("grade") String grade,
                @Param("due") Instant dueAt);
}
