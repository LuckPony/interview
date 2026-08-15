package interview.homegrown.modules.drill.service;

import interview.homegrown.modules.drill.domain.Concept;
import interview.homegrown.modules.drill.domain.Corpus;
import interview.homegrown.modules.drill.domain.CorpusChunk;
import interview.homegrown.modules.drill.domain.Mastery;
import interview.homegrown.modules.drill.domain.StudyPlan;
import interview.homegrown.modules.drill.domain.WebContent;
import interview.homegrown.modules.drill.repository.ConceptChunkRepository;
import interview.homegrown.modules.drill.repository.ConceptRepository;
import interview.homegrown.modules.drill.repository.CorpusChunkRepository;
import interview.homegrown.modules.drill.repository.CorpusRepository;
import interview.homegrown.modules.drill.repository.MasteryRepository;
import interview.homegrown.modules.drill.repository.StudyPlanRepository;
import interview.homegrown.modules.drill.repository.WebContentRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 学习上下文注入层：把「学生画像 + 概念骨架 + 命中资料块 + 互联网补充内容」组装成一段
 * 结构化上下文，供出题 / 对话 / 判分 / 复盘四处 AI 调用统一注入。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>不截断语义</b>：资料按概念命中块（块是完整逻辑主题，检索而非硬切）；</li>
 *   <li><b>素材不锁死</b>：资料 + 互联网都是「素材」，prompt 指令层允许通用知识照常考；</li>
 *   <li><b>按场景截断</b>：资料块合计 ≤{@link #MAX_CHUNK_CHARS}、互联网 ≤{@link #MAX_WEB_CHARS}、
 *       画像 ≤{@link #MAX_PROFILE_CHARS}，控制每轮调用 token。</li>
 * </ul>
 */
@Service
public class ProgressContextService {

    /** 命中资料块合计注入上限（字符） */
    static final int MAX_CHUNK_CHARS = 8000;
    /** 互联网补充注入上限（字符） */
    static final int MAX_WEB_CHARS = 4000;
    /** 学生画像注入上限（字符） */
    static final int MAX_PROFILE_CHARS = 500;

    private final MasteryRepository masteryRepo;
    private final ConceptRepository conceptRepo;
    private final StudyPlanRepository planRepo;
    private final CorpusRepository corpusRepo;
    private final CorpusChunkRepository chunkRepo;
    private final ConceptChunkRepository conceptChunkRepo;
    private final WebContentRepository webRepo;

    public ProgressContextService(MasteryRepository masteryRepo, ConceptRepository conceptRepo,
                                  StudyPlanRepository planRepo, CorpusRepository corpusRepo,
                                  CorpusChunkRepository chunkRepo, ConceptChunkRepository conceptChunkRepo,
                                  WebContentRepository webRepo) {
        this.masteryRepo = masteryRepo;
        this.conceptRepo = conceptRepo;
        this.planRepo = planRepo;
        this.corpusRepo = corpusRepo;
        this.chunkRepo = chunkRepo;
        this.conceptChunkRepo = conceptChunkRepo;
        this.webRepo = webRepo;
    }

    /**
     * 组装上下文。conceptIds 为空返回 null（调用方不注入）。
     * 每段缺失都优雅降级：没有资料/没有互联网/没有画像都不阻塞。
     */
    public String contextFor(Long userId, List<Long> conceptIds) {
        if (userId == null || conceptIds == null || conceptIds.isEmpty()) return null;
        List<Concept> concepts = conceptIds.stream()
                .map(conceptRepo::findById)
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get)
                .toList();
        if (concepts.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("【学生当前进度】\n").append(profile(userId, concepts));
        sb.append("\n\n【概念要点】\n").append(skeleton(concepts));
        String ref = material(concepts);
        if (ref != null) sb.append("\n\n【学习资料（用户上传，作为素材之一）】\n").append(ref);
        String web = internet(concepts);
        if (web != null) sb.append("\n\n【互联网补充（预取，作为素材之一）】\n").append(web);
        return sb.toString().trim();
    }

    /** 单概念快捷版。 */
    public String contextFor(Long userId, Long conceptId) {
        if (conceptId == null) return null;
        return contextFor(userId, List.of(conceptId));
    }

    // ------------------------------------------------------------ 画像

    private String profile(Long userId, List<Concept> concepts) {
        List<String> lines = new ArrayList<>();
        for (Concept c : concepts) {
            Mastery m = masteryRepo.findByUserIdAndConceptId(userId, c.getId()).orElse(null);
            String level = m == null ? "0（未学）" : String.valueOf(m.getMasteryLevel());
            String grade = m != null && m.getLastGrade() != null ? m.getLastGrade().name() : "无";
            String due = m != null && m.getDueAt() != null ? String.valueOf(m.getDueAt()) : "无";
            lines.add(String.format("- 「%s」(L%d)：掌握度 %s/3，最近判分 %s，复习 %s",
                    c.getName(), c.getLayer(), level, grade, due));
        }
        // 方向层掌握率（首个概念的所属方向）
        Concept first = concepts.get(0);
        if (first.getStudyPlanId() != null) {
            StudyPlan plan = planRepo.findById(first.getStudyPlanId()).orElse(null);
            if (plan != null) {
                lines.add("- 方向「" + plan.getTitle() + "」层掌握率：" + layerRatios(userId, first.getStudyPlanId()));
            }
        }
        return truncate(String.join("\n", lines), MAX_PROFILE_CHARS);
    }

    /** 层掌握率摘要：L1 60% L2 20%（写达标线 mastery_level>=2，与每日出题口径一致）。 */
    private String layerRatios(Long userId, Long planId) {
        List<Concept> all = conceptRepo.findByStudyPlanId(planId);
        if (all.isEmpty()) return "（无概念）";
        List<Mastery> mastery = masteryRepo.findByUserId(userId);
        Set<Long> masteredIds = mastery.stream()
                .filter(m -> m.getMasteryLevel() >= 2)
                .map(Mastery::getConceptId)
                .collect(Collectors.toSet());
        Map<Integer, List<Concept>> byLayer = all.stream()
                .collect(Collectors.groupingBy(Concept::getLayer));
        return byLayer.keySet().stream().sorted().map(layer -> {
            List<Concept> cs = byLayer.get(layer);
            long done = cs.stream().filter(c -> masteredIds.contains(c.getId())).count();
            return "L" + layer + " " + Math.round(done * 100.0 / cs.size()) + "%";
        }).collect(Collectors.joining(" "));
    }

    // ------------------------------------------------------------ 概念骨架

    private String skeleton(List<Concept> concepts) {
        return concepts.stream().map(c -> {
            String desc = c.getDescription() == null || c.getDescription().isBlank()
                    ? "（无说明）" : c.getDescription();
            return "- " + c.getName() + "（主题：" + c.getTopic() + "，认知层 L" + c.getLayer() + "）：" + desc;
        }).collect(Collectors.joining("\n"));
    }

    // ------------------------------------------------------------ 资料块（按概念命中）

    private String material(List<Concept> concepts) {
        List<Long> conceptIds = concepts.stream().map(Concept::getId).toList();
        List<Long> chunkIds = conceptChunkRepo.chunkIdsOfConcepts(conceptIds);
        if (chunkIds.isEmpty()) {
            // 兜底：概念名 × 块 topic 的模糊匹配尚未建立（如索引未完成）时，用资料总览
            return overviewOnly(concepts);
        }
        List<CorpusChunk> chunks = new ArrayList<>(chunkRepo.findAllById(chunkIds));
        chunks.sort(Comparator.comparingInt(CorpusChunk::getSeq));
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (CorpusChunk c : chunks) {
            String head = "- 《" + (c.getTitle() == null ? "" : c.getTitle()) + "》"
                    + (c.getSummary() == null || c.getSummary().isBlank() ? "" : "（" + c.getSummary() + "）")
                    + "\n" + c.getText();
            if (used + head.length() > MAX_CHUNK_CHARS) {
                sb.append(head, 0, Math.min(head.length(), MAX_CHUNK_CHARS - used)).append("…（截断）\n");
                break;
            }
            sb.append(head).append("\n\n");
            used += head.length();
        }
        return sb.toString().isBlank() ? null : sb.toString().trim();
    }

    /** 索引未完成时的兜底：概念所属方向的整篇资料前 8k 字（退化为既有全文注入）。 */
    private String overviewOnly(List<Concept> concepts) {
        Concept first = concepts.get(0);
        if (first.getStudyPlanId() == null) return null;
        StudyPlan plan = planRepo.findById(first.getStudyPlanId()).orElse(null);
        if (plan == null || plan.getCorpusId() == null) return null;
        Corpus corpus = corpusRepo.findById(plan.getCorpusId()).orElse(null);
        if (corpus == null || corpus.getText() == null) return null;
        String t = corpus.getText();
        String head = "《" + corpus.getName() + "》\n";
        if (t.length() <= MAX_CHUNK_CHARS) return head + t;
        return head + t.substring(0, MAX_CHUNK_CHARS) + "\n…（资料较长，已截断）";
    }

    // ------------------------------------------------------------ 互联网补充

    private String internet(List<Concept> concepts) {
        List<Long> conceptIds = concepts.stream().map(Concept::getId).toList();
        List<WebContent> webs = webRepo.findByConceptIdIn(conceptIds);
        if (webs.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (WebContent w : webs) {
            String head = "- " + (w.getTitle() == null ? "" : w.getTitle())
                    + (w.getUrl() == null || w.getUrl().isBlank() ? "" : "（来源：" + w.getUrl() + "）")
                    + "\n" + w.getText();
            if (used + head.length() > MAX_WEB_CHARS) {
                sb.append(head, 0, Math.min(head.length(), MAX_WEB_CHARS - used)).append("…（截断）\n");
                break;
            }
            sb.append(head).append("\n\n");
            used += head.length();
        }
        return sb.toString().isBlank() ? null : sb.toString().trim();
    }

    private String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max) + "…";
    }
}
