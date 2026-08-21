package interview.homegrown.modules.knowledge.repository;

import interview.homegrown.modules.knowledge.domain.KnowledgeCard;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface KnowledgeCardRepository extends JpaRepository<KnowledgeCard,Long> {

    List<KnowledgeCard> findByUserIdOrderByCreatedAtDesc(Long userId);

    //到期 待复习卡片
    List<KnowledgeCard> findByUserIdAndDueAtBeforeOrderByDueAtAsc(Long userId, Instant now);

    List<KnowledgeCard> findByUserIdAndPlanIdOrderByCreatedAtDesc(Long userId, Long planId);
}
