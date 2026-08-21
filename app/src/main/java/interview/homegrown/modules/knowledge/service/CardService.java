package interview.homegrown.modules.knowledge.service;

import interview.homegrown.common.exception.BusinessException;
import interview.homegrown.common.exception.ErrorCode;
import interview.homegrown.modules.knowledge.domain.KnowledgeCard;
import interview.homegrown.modules.knowledge.repository.KnowledgeCardRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

// 知识卡片：查询 / 编辑 / 删除 / 间隔重复调度。
@Service
public class CardService {

    //复习间隔（天）：按 reviewCount 递增，封顶
    private static final int[] INTERVALS = {1,3,7,15,30};

    private final KnowledgeCardRepository cardRepo;

    public CardService(KnowledgeCardRepository cardRepo) {
        this.cardRepo = cardRepo;
    }

    //卡片权限校验拦截器
    private KnowledgeCard requireOwned(Long userId, Long cardId) {
        KnowledgeCard card = cardRepo.findById(cardId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "卡片不存在"));
        if (!card.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权操作该卡片");
        }
        return card;
    }

    //查找所有知识卡片或者查找和制定计划相关的知识卡片
    public List<KnowledgeCard> list(Long userId, Long planId) {
        if (planId != null) {
            return cardRepo.findByUserIdAndPlanIdOrderByCreatedAtDesc(userId, planId);
        }
        return cardRepo.findByUserIdOrderByCreatedAtDesc(userId);
    }

    //查找该复习的知识卡片
    public List<KnowledgeCard> due(Long userId) {
        return cardRepo.findByUserIdAndDueAtBeforeOrderByDueAtAsc(userId, Instant.now());
    }

    //更新卡片
    public KnowledgeCard update(Long userId,Long cardId,String question,String answer, String tags, Long planId){

        KnowledgeCard card = requireOwned(userId,cardId);
        if (question != null) card.setQuestion(question);
        if (answer != null) card.setAnswer(answer);
        if (tags != null) card.setTags(tags);
        if (planId != null) card.setPlanId(planId);
        return cardRepo.save(card);

    }

    //删除卡片
    public void delete(Long userId, Long cardId) {
        KnowledgeCard card = requireOwned(userId, cardId);
        cardRepo.delete(card);
    }

    //复习反馈：掌握 → 按档位推后；没掌握 → 明天重来。
    public KnowledgeCard review(Long userId, Long cardId, boolean mastered){
        KnowledgeCard card = requireOwned(userId, cardId);
        Instant now = Instant.now();
        card.setLastReviewedAt(now);

        if(mastered){
            int idx = Math.min(card.getReviewCount(),INTERVALS.length-1);
            card.setDueAt(now.plus(INTERVALS[idx], ChronoUnit.DAYS));
            card.setReviewCount(card.getReviewCount()+1);
        }else{
            card.setDueAt(now.plus(1,ChronoUnit.DAYS));
            card.setReviewCount(0);
        }

        return cardRepo.save(card);
    }
}
