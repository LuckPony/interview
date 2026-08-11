package interview.homegrown.modules.drill.service;

import interview.homegrown.common.ai.StructuredOutputInvoker;
import interview.homegrown.modules.drill.domain.Concept;
import interview.homegrown.modules.drill.domain.Corpus;
import interview.homegrown.modules.drill.domain.Mastery;
import interview.homegrown.modules.drill.domain.StudyPlan;
import interview.homegrown.modules.drill.repository.ConceptRepository;
import interview.homegrown.modules.drill.repository.CorpusRepository;
import interview.homegrown.modules.drill.repository.MasteryRepository;
import interview.homegrown.modules.drill.repository.StudyPlanRepository;
import interview.homegrown.modules.drill.web.dto.ChatMessage;
import interview.homegrown.modules.drill.web.dto.IntakeResponse;
import interview.homegrown.modules.drill.web.dto.PlanConceptView;
import interview.homegrown.modules.drill.web.dto.PlanPoint;
import interview.homegrown.modules.drill.web.dto.PlanView;
import interview.homegrown.modules.drill.web.dto.StudyPlanDraft;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.List;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * 痛点 1 的「AI 确认需求」落地：学习方向由多轮对话动态生成，不再写死成固定目录。
 *
 * <p>决策权边界：LLM 只生成「学什么」（plan 内容 + 每个 point 的层级建议），
 * 出题/判分等仍由确定性服务端算法负责（intake 不碰）。
 *
 * <p>intake 无状态：前端每轮把完整 messages 发来，这里拼成一段 user prompt 交给
 * StructuredOutputInvoker，强制返回 {reply, draft}。draft 非空即信息够了。
 * 若绑定了资料（corpusId），把解析文本注入 prompt，让规划贴合用户真实材料。
 */
@Service
public class StudyPlanService {

    private static final Logger log = LoggerFactory.getLogger(StudyPlanService.class);

    /** 层级刻度：1=概念 2=机制 3=实现 4=权衡 5=故障排查 */
    private static final int LAYER_MIN = 1;
    private static final int LAYER_MAX = 5;

    private static final String SYSTEM_PROMPT = """
            你是一个面试备考的「学习规划顾问」。目标是通过简短的多轮对话，弄清楚用户想学哪个方向、
            当前基础、以及目标，然后帮他拆出一份可执行的层级学习规划。

            对话规则：
            - 每次都用中文在 reply 字段里自然回应：可以追问（基础/目标/范围），也可以肯定、澄清。
            - 当用户提供了参考资料时，你的规划必须紧扣资料的实际内容，不要脱离资料空谈、也不要编造资料里没有的章节。
            - 当你判断信息已经足够形成规划时，除了 reply，还要在 draft 字段填上结构化规划：
              * title：方向名（简短，如「前端」「微服务」「MySQL 调优」）
              * goal：一句话学习目标
              * points：4-8 个知识点，每个含 name（知识点名）、layer（1-5 的真实认知层，不要虚高）、
                note（一句话提示，可空）
            - 如果信息还不够，draft 必须填 null，继续用 reply 追问。
            - 只做规划，不要出具体面试题、不要给答案。严格遵循格式说明的 JSON。""";

    private final StudyPlanRepository planRepo;
    private final ConceptRepository conceptRepo;
    private final MasteryRepository masteryRepo;
    private final CorpusRepository corpusRepo;
    private final CorpusService corpusService;
    private final StructuredOutputInvoker invoker;

    public StudyPlanService(StudyPlanRepository planRepo, ConceptRepository conceptRepo,
                            MasteryRepository masteryRepo, CorpusRepository corpusRepo,
                            CorpusService corpusService, StructuredOutputInvoker invoker) {
        this.planRepo = planRepo;
        this.conceptRepo = conceptRepo;
        this.masteryRepo = masteryRepo;
        this.corpusRepo = corpusRepo;
        this.corpusService = corpusService;
        this.invoker = invoker;
    }

    /** 无状态 intake：把前端发来的完整对话拼成 user prompt；若绑定了资料，把资料文本注入作为规划依据。 */
    public IntakeResponse intake(List<ChatMessage> messages, Long corpusId) {
        String history = messages.stream()
                .map(m -> (m.role().equals("user") ? "用户" : "顾问") + "：" + m.content())
                .collect(Collectors.joining("\n"));
        String ref = corpusService.referenceWithName(corpusId);
        String userPrompt = (ref == null) ? history
                : history + "\n\n【参考资料】用户上传了如下资料，请基于它的真实内容来规划学习方向，不要脱离资料空谈：\n" + ref;
        return invoker.invoke(SYSTEM_PROMPT, userPrompt, IntakeResponse.class);
    }

    /** 确认落库：clamp layer∈[1,5]，同用户同名方向幂等（避免双击重复建），绑定资料。 */
    @Transactional
    public PlanView confirm(Long userId, StudyPlanDraft draft) {
        if (draft == null || draft.title() == null || draft.title().isBlank()) {
            throw new IllegalArgumentException("规划标题不能为空");
        }
        StudyPlan plan = planRepo.findByUserIdAndTitle(userId, draft.title().trim())
                .orElseGet(() -> {
                    StudyPlan p = new StudyPlan();
                    p.setUserId(userId);
                    p.setTitle(draft.title().trim());
                    p.setGoal(draft.goal());
                    p.setStatus("ACTIVE");
                    return planRepo.save(p);
                });
        // 绑定资料（用户基于某本书 / 项目资料学习）；draft 未带则不覆盖已有绑定。
        if (draft.corpusId() != null) {
            plan.setCorpusId(draft.corpusId());
            plan = planRepo.save(plan);
        }

        // 已存在的点不重复插（按 name 去重，兼容「继续聊后又确认」）
        List<String> existing = conceptRepo.findByStudyPlanId(plan.getId()).stream()
                .map(Concept::getName).collect(Collectors.toList());

        for (PlanPoint pt : draft.points()) {
            if (pt == null || pt.name() == null || pt.name().isBlank()) continue;
            if (existing.contains(pt.name().trim())) continue;
            Concept c = new Concept();
            c.setTopic(plan.getTitle());
            c.setLayer(clampLayer(pt.layer()));
            c.setName(pt.name().trim());
            c.setDescription(pt.note());
            c.setStudyPlanId(plan.getId());
            conceptRepo.save(c);
            existing.add(pt.name().trim());
        }
        return toView(userId, plan);
    }

    public List<PlanView> list(Long userId) {
        return planRepo.findByUserId(userId).stream()
                .map(p -> toView(userId, p))
                .sorted(Comparator.comparing(PlanView::id))
                .toList();
    }

    // ---------------------------------------------------- 用户手动编辑（痛点 1 的自主权）

    /** 编辑方向：改标题/目标。标题改动会同步该方向下所有知识点的 topic，保持一致。 */
    @Transactional
    public PlanView updatePlan(Long userId, Long planId, String title, String goal) {
        StudyPlan plan = requireOwnedPlan(userId, planId);
        if (title != null && !title.isBlank()) {
            String old = plan.getTitle();
            plan.setTitle(title.trim());
            if (!old.equals(plan.getTitle())) {
                for (Concept c : conceptRepo.findByStudyPlanId(plan.getId())) {
                    c.setTopic(plan.getTitle());
                    conceptRepo.save(c);
                }
            }
        }
        plan.setGoal(goal);
        planRepo.save(plan);
        return toView(userId, plan);
    }

    /** 删除方向：先删该方向下的知识点（连带各自的掌握度记录），再删方向。 */
    @Transactional
    public void deletePlan(Long userId, Long planId) {
        StudyPlan plan = requireOwnedPlan(userId, planId);
        for (Concept c : conceptRepo.findByStudyPlanId(plan.getId())) {
            masteryRepo.deleteByConceptId(c.getId());
            conceptRepo.delete(c);
        }
        planRepo.delete(plan);
    }

    /** 给方向新增一个知识点。 */
    @Transactional
    public PlanView addConcept(Long userId, Long planId, String name, Integer layer, String note) {
        StudyPlan plan = requireOwnedPlan(userId, planId);
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "知识点名称不能为空");
        }
        Concept c = new Concept();
        c.setTopic(plan.getTitle());
        c.setLayer(clampLayer(layer == null ? 1 : layer));
        c.setName(name.trim());
        c.setDescription(note);
        c.setStudyPlanId(plan.getId());
        conceptRepo.save(c);
        return toView(userId, plan);
    }

    /** 编辑知识点：改名称/认知层/提示。只允许编辑当前用户方向下的知识点。 */
    @Transactional
    public PlanView updateConcept(Long userId, Long conceptId, String name, Integer layer, String note) {
        Concept c = requireOwnedConcept(userId, conceptId);
        if (name != null && !name.isBlank()) c.setName(name.trim());
        if (layer != null) c.setLayer(clampLayer(layer));
        c.setDescription(note);
        conceptRepo.save(c);
        StudyPlan plan = planRepo.findById(c.getStudyPlanId())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "学习方向不存在"));
        return toView(userId, plan);
    }

    /** 删除知识点：连带清掉它的掌握度记录（FK）。 */
    @Transactional
    public void deleteConcept(Long userId, Long conceptId) {
        Concept c = requireOwnedConcept(userId, conceptId);
        masteryRepo.deleteByConceptId(c.getId());
        conceptRepo.delete(c);
    }

    // —— 内部 ——

    /** 取当前用户的方向，不存在或不属于该用户一律 404。 */
    private StudyPlan requireOwnedPlan(Long userId, Long planId) {
        return planRepo.findById(planId)
                .filter(p -> p.getUserId().equals(userId))
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "学习方向不存在"));
    }

    /** 取当前用户方向下的知识点（全局种子概念 study_plan_id 为 null，不可编辑）。 */
    private Concept requireOwnedConcept(Long userId, Long conceptId) {
        Concept c = conceptRepo.findById(conceptId)
                .filter(c2 -> c2.getStudyPlanId() != null)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "知识点不存在"));
        requireOwnedPlan(userId, c.getStudyPlanId());   // 校验方向归属
        return c;
    }

    private PlanView toView(Long userId, StudyPlan plan) {
        List<Mastery> allM = masteryRepo.findByUserId(userId);
        Instant now = Instant.now();
        Map<Long, Integer> lvl = allM.stream()
                .collect(Collectors.toMap(Mastery::getConceptId, Mastery::getMasteryLevel,
                        (a, b) -> a));
        List<PlanConceptView> concepts = conceptRepo.findByStudyPlanId(plan.getId()).stream()
                .map(c -> new PlanConceptView(c.getId(), c.getName(), c.getTopic(), c.getLayer(),
                        lvl.getOrDefault(c.getId(), 0), c.getDescription()))
                .sorted(Comparator.comparingInt(PlanConceptView::layer))
                .toList();
        long mastered = concepts.stream().filter(c -> c.masteryLevel() > 0).count();
        // 待复习 = 本方向内「已掌握(level>0)且到期(dueAt<=now)」的概念数，供前端置灰「复习」按钮
        Set<Long> planIds = concepts.stream().map(PlanConceptView::id).collect(Collectors.toSet());
        long due = allM.stream()
                .filter(m -> planIds.contains(m.getConceptId()))
                .filter(m -> lvl.getOrDefault(m.getConceptId(), 0) > 0)
                .filter(m -> m.getDueAt() != null && !m.getDueAt().isAfter(now))
                .count();
        // 资料名：方向绑了书/项目资料时展示
        String corpusName = null;
        if (plan.getCorpusId() != null) {
            Corpus c = corpusRepo.findById(plan.getCorpusId()).orElse(null);
            corpusName = c == null ? null : c.getName();
        }
        return new PlanView(plan.getId(), plan.getTitle(), plan.getGoal(), concepts,
                (int) mastered, concepts.size(), (int) due, corpusName);
    }

    private int clampLayer(int layer) {
        if (layer < LAYER_MIN) return LAYER_MIN;
        if (layer > LAYER_MAX) return LAYER_MAX;
        return layer;
    }
}
