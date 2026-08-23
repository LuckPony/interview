package interview.homegrown.modules.drill.repository;

import interview.homegrown.modules.drill.domain.Grade;
import interview.homegrown.modules.drill.domain.Mastery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MasteryRepository extends JpaRepository<Mastery, Long> {

    List<Mastery> findByUserId(Long userId);

    Optional<Mastery> findByUserIdAndConceptId(Long userId, Long conceptId);

    // 删除知识点时连带清掉它的掌握度记录（mastery.concept_id 有 FK 到 concept）
    void deleteByConceptId(Long conceptId);

    /**
     * upsert：首次插入，重复则覆盖（深度画像与排程都靠它）。
     * 纯 JPA 实现（先查后 save），避免依赖数据库方言专有的 upsert 语法。
     */
    @Transactional
    default void upsert(Long userId, Long conceptId, int level, String grade, Instant dueAt) {
        Mastery m = findByUserIdAndConceptId(userId, conceptId).orElseGet(Mastery::new);
        if (m.getId() == null) {
            m.setUserId(userId);
            m.setConceptId(conceptId);
        }
        m.setMasteryLevel(level);
        try {
            m.setLastGrade(grade == null || grade.isBlank() ? null : Grade.valueOf(grade));
        } catch (IllegalArgumentException e) {
            m.setLastGrade(null);
        }
        m.setDueAt(dueAt);
        save(m);
    }
}
