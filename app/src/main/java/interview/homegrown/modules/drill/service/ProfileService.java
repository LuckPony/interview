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

    // 一个主题：最大已掌握层 + 每概念明细
    public record TopicProfile(String topic, int masteredLayer, List<ConceptProfile> concepts) {
    }

    public record ConceptProfile(Long conceptId, String name, int layer, int masteryLevel) {
    }
}
