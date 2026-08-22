package interview.homegrown.modules.knowledge.service;

import interview.homegrown.common.ai.StructuredOutputInvoker;
import interview.homegrown.common.exception.BusinessException;
import interview.homegrown.common.exception.ErrorCode;
import interview.homegrown.modules.drill.domain.Concept;
import interview.homegrown.modules.drill.repository.ConceptRepository;
import interview.homegrown.modules.knowledge.domain.KnowledgeCard;
import interview.homegrown.modules.knowledge.repository.KnowledgeCardRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;


//把一段日常对话收敛为一张知识卡片，并尝试关联到已有概念（不相关则不关联）。
@Service
public class ChatCaptureService {

    private static final long FIRST_REVIEW_DAYS = 3;

    private final KnowledgeCardRepository cardRepo;
    private final ConceptRepository conceptRepo;
    private final StructuredOutputInvoker invoker;

    public ChatCaptureService(KnowledgeCardRepository cardRepo, ConceptRepository conceptRepo, StructuredOutputInvoker invoker) {
        this.cardRepo = cardRepo;
        this.conceptRepo = conceptRepo;
        this.invoker = invoker;
    }

    //定义功能函数和记录
    public record Message(String role, String content) {
    }

    public record CardDraft(String question, String answer, List<String> tags) {
    }

    public record ConceptMatch(Long conceptId) {
    }

    //LLM 判断标签与哪个已有概念相关；都不相关返回 null。
    private Long matchConcept(List<String> tags) {

        List<Concept> concepts = conceptRepo.findAll();
        if (concepts.isEmpty()) return null;
        String candidates = concepts.stream()
                .map(c -> c.getId() + ":" + c.getTopic() + "/" + c.getName())
                .limit(50)
                .reduce("", (a, b) -> a + b + "\n");
        ConceptMatch result = invoker.invoke(
                "候选概念列表（id:主题/名称）：\n" + candidates
                        + "\n\n请判断这些标签最相关的一个概念 id；若都不相关返回 -1。只返回 JSON {\"conceptId\": 数字}",
                String.join(",", tags),
                ConceptMatch.class);
        return result.conceptId() != null && result.conceptId() > 0 ? result.conceptId() : null;
    }

    public KnowledgeCard capture(Long userId, List<Message> conversation) {

        String raw = conversation.stream()
                .map(m -> (m.role().equals("user") ? "我：" : "AI:") + m.content())
                .reduce("", (a, b) -> a + b + "\n");

        //LLM提炼成结构化卡片
        CardDraft draft = invoker.invoke(
                "你是一个知识整理助手。把下面的对话提炼成一张知识卡片："
                        + "question(一句话问题/要点)、answer(精简回答，1-3句)、tags(2-4个标签)。"
                        + "注意：输出值不要带任何字段名前缀（如 question:、answer:、tags:）。"
                        + "若对话无实质内容则返回空 question。",
                raw,
                CardDraft.class);

        // 无实质内容的对话（闲聊/寒暄/一次性事务）：不落库，直接提示用户，避免沉淀一堆空卡片
        if (draft.question() == null || draft.question().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "这段对话没有值得沉淀的内容，未生成卡片");
        }

        //尝试关联概念（不相关返回NULL,不影响画像）
        Long conceptId = (draft.tags() == null || draft.tags().isEmpty())
                ? null
                : matchConcept(draft.tags());

        //落库
        KnowledgeCard card = new KnowledgeCard();
        card.setUserId(userId);
        card.setQuestion(stripFieldPrefix(draft.question()));
        card.setAnswer(stripFieldPrefix(draft.answer()));
        card.setTags(draft.tags() == null
                ? ""
                : draft.tags().stream()
                    .map(this::stripFieldPrefix)
                    .filter(t -> !t.isBlank())
                    .distinct()
                    .collect(Collectors.joining(",")));
        card.setConceptId(conceptId);
        card.setDueAt(Instant.now().plus(FIRST_REVIEW_DAYS, ChronoUnit.DAYS));
        return cardRepo.save(card);
    }

    /** 去掉模型可能在字段值里加上的名字前缀（question:/问题：/answer:/tags: 等），避免存进卡片内容。 */
    private String stripFieldPrefix(String s) {
        if (s == null) return null;
        String t = s.trim();
        t = t.replaceFirst("^(?i)(question|问题|answer|答案|tags|标签)\\s*[:：]\\s*", "");
        return t.trim();
    }
}