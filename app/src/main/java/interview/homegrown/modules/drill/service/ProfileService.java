package interview.homegrown.modules.drill.service;

import interview.homegrown.modules.drill.domain.Concept;
import interview.homegrown.modules.drill.domain.Mastery;
import interview.homegrown.modules.drill.repository.ConceptRepository;
import interview.homegrown.modules.drill.repository.MasteryRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 深度画像：GROUP BY topic, MAX(layer) 直出，不建 Elo（layer 本身就是难度刻度）。
 * 量化"你在这个主题上，已掌握到第几层"——这是痛点 3 的核心产出。
 */
@Service
public class ProfileService {

    private final MasteryRepository masteryRepo;
    private final ConceptRepository conceptRepo;

    public ProfileService(MasteryRepository masteryRepo, ConceptRepository conceptRepo) {
        this.masteryRepo = masteryRepo;
        this.conceptRepo = conceptRepo;
    }

    public List<TopicProfile> profile(Long userId) {
        List<Mastery> mastered = masteryRepo.findByUserId(userId);
        Map<Long, Mastery> byConcept = mastered.stream()
                .collect(Collectors.toMap(Mastery::getConceptId, m -> m));

        List<Concept> concepts = conceptRepo.findAll();

        Map<String, List<ConceptProfile>> byTopic = new HashMap<>();
        Map<String, Integer> masteredLayer = new HashMap<>();

        for (Concept c : concepts) {
            Mastery m = byConcept.get(c.getId());
            int level = m == null ? 0 : m.getMasteryLevel();
            byTopic.computeIfAbsent(c.getTopic(), k -> new ArrayList<>())
                    .add(new ConceptProfile(c.getId(), c.getName(), c.getLayer(), level));
            if (level > 0) {
                masteredLayer.merge(c.getTopic(), c.getLayer(), Math::max);
            }
        }

        return byTopic.entrySet().stream()
                .map(e -> {
                    e.getValue().sort(Comparator.comparingInt(ConceptProfile::layer));
                    return new TopicProfile(e.getKey(),
                            masteredLayer.getOrDefault(e.getKey(), 0),
                            e.getValue());
                })
                .sorted(Comparator.comparing(TopicProfile::topic))
                .toList();
    }

    /**
     * 能力画像 md 文档：把用户已掌握（masteryLevel&gt;0）的知识点聚合成一份"已训练能力"清单。
     * 用于用户画像页展示，并作为能力画像注入下次出题 prompt（苏格拉底"基于已学知识点考查"的锚点来源）。
     */
    public String skillDoc(Long userId) {
        List<Mastery> mastered = masteryRepo.findByUserId(userId).stream()
                .filter(m -> m.getMasteryLevel() > 0)
                .sorted(Comparator.comparing(Mastery::getUpdatedAt).reversed())
                .toList();
        if (mastered.isEmpty()) {
            return "（用户暂无已掌握的知识点）";
        }

        Map<Long, Mastery> byConcept = mastered.stream()
                .collect(Collectors.toMap(Mastery::getConceptId, m -> m));
        Map<Long, Concept> conceptMap = conceptRepo.findAll().stream()
                .collect(Collectors.toMap(Concept::getId, c -> c));

        Map<String, List<Concept>> byTopic = new HashMap<>();
        for (Mastery m : mastered) {
            Concept c = conceptMap.get(m.getConceptId());
            if (c == null) continue;
            byTopic.computeIfAbsent(c.getTopic(), k -> new ArrayList<>()).add(c);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# 能力画像（已训练能力）\n\n");
        sb.append("> 按主题分组，列出用户已掌握的知识点与掌握层级（L1-L5）。"
                + "用于后续教学「基于已学知识点」考查的锚点参考。\n\n");

        byTopic.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> {
                    String topic = e.getKey();
                    e.getValue().sort(Comparator.comparingInt(Concept::getLayer));
                    int maxLayer = e.getValue().stream()
                            .mapToInt(Concept::getLayer).max().orElse(0);
                    sb.append("## ").append(topic).append("（已掌握至 L").append(maxLayer).append("）\n\n");
                    for (Concept c : e.getValue()) {
                        Mastery m = byConcept.get(c.getId());
                        sb.append("- **").append(c.getName()).append("**")
                                .append("（L").append(c.getLayer()).append("，掌握度 ")
                                .append(m == null ? 0 : m.getMasteryLevel())
                                .append("）\n");
                    }
                    sb.append("\n");
                });

        return sb.toString();
    }

    // 一个主题：最大已掌握层 + 每概念明细
    public record TopicProfile(String topic, int masteredLayer, List<ConceptProfile> concepts) {
    }

    public record ConceptProfile(Long conceptId, String name, int layer, int masteryLevel) {
    }
}
