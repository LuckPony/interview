package interview.homegrown.modules.drill.service;

import interview.homegrown.modules.drill.domain.Concept;
import interview.homegrown.modules.drill.domain.ConceptRef;
import interview.homegrown.modules.drill.domain.ConceptRole;
import interview.homegrown.modules.drill.domain.Mastery;
import interview.homegrown.modules.drill.domain.SelectedTask;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 组合题策略（纯确定性，零 LLM）。
 *
 * <p>三条铁律：
 * <ol>
 *   <li><b>关联来自结构，不随机配对。</b>只有两种合法伙伴：同 topic 跨层（层差 1-2）、
 *       同 layer 跨 topic 且 layer&gt;=4（权衡/故障层才有跨主题可比性）。</li>
 *   <li><b>一题最多一个新点。</b>新点定义 mastery_level&lt;1。primary 是新点时伙伴必须是锚点
 *       （level&gt;=2），这就是"挂靠学习"：把新知识挂到已掌握的桩上。</li>
 *   <li><b>arity 不会自己长大，必须强制驱动。</b>primary 已达 L2（单点写达标）时强制 arity&gt;=2，
 *       否则用户永远停在单点题里，"变形复习"（痛点 2）落不了地。</li>
 * </ol>
 *
 * <p>随机数用 (userId, conceptId, 已练次数) 做种子，同一状态下结果可复现，便于排查。
 */
@Component
public class CombinationPolicy {

    /** 非强制情况下升到 arity>=2 的比例 */
    private static final double COMBINE_RATIO = 0.35;
    /** 在已决定组合的前提下，再升到 arity=3 的比例 */
    private static final double TRIPLE_RATIO = 0.25;
    /** 同 topic 跨层允许的最大层差 */
    private static final int MAX_LAYER_GAP = 2;
    /** 同 layer 跨 topic 只在这一层以上才合法 */
    private static final int CROSS_TOPIC_MIN_LAYER = 4;
    /** 锚点门槛：写达标 */
    private static final int ANCHOR_LEVEL = 2;

    public SelectedTask build(Concept primary, List<Concept> all, Map<Long, Mastery> masteryByConcept) {
        int primaryLevel = level(masteryByConcept, primary.getId());
        boolean primaryIsNew = primaryLevel < 1;

        List<Concept> candidates = legalPartners(primary, all).stream()
                .filter(c -> admissible(primaryIsNew, level(masteryByConcept, c.getId())))
                .sorted(partnerOrder(primary, masteryByConcept))
                .toList();

        int arity = decideArity(primary, primaryLevel, candidates.size());

        List<ConceptRef> refs = new ArrayList<>();
        refs.add(ConceptRef.of(primary, ConceptRole.PRIMARY));
        for (int i = 0; i < arity - 1 && i < candidates.size(); i++) {
            refs.add(ConceptRef.of(candidates.get(i), ConceptRole.ANCHOR));
        }
        return new SelectedTask(List.copyOf(refs));
    }

    /** 铁律 1：合法伙伴只能来自结构关联 */
    private List<Concept> legalPartners(Concept primary, List<Concept> all) {
        return all.stream()
                .filter(c -> !c.getId().equals(primary.getId()))
                .filter(c -> sameTopicNearLayer(primary, c) || sameLayerCrossTopicDeep(primary, c))
                .toList();
    }

    private boolean sameTopicNearLayer(Concept p, Concept c) {
        return p.getTopic().equals(c.getTopic())
                && Math.abs(p.getLayer() - c.getLayer()) <= MAX_LAYER_GAP
                && p.getLayer() != c.getLayer();
    }

    private boolean sameLayerCrossTopicDeep(Concept p, Concept c) {
        return !p.getTopic().equals(c.getTopic())
                && p.getLayer() == c.getLayer()
                && p.getLayer() >= CROSS_TOPIC_MIN_LAYER;
    }

    /** 铁律 2：一题最多一个新点 */
    private boolean admissible(boolean primaryIsNew, int partnerLevel) {
        return primaryIsNew ? partnerLevel >= ANCHOR_LEVEL : partnerLevel >= 1;
    }

    /** 伙伴排序：同 topic 优先 -> 层差小优先 -> 掌握度高优先（锚点越稳越好挂） */
    private Comparator<Concept> partnerOrder(Concept primary, Map<Long, Mastery> mastery) {
        return Comparator
                .comparing((Concept c) -> primary.getTopic().equals(c.getTopic()) ? 0 : 1)
                .thenComparingInt(c -> Math.abs(primary.getLayer() - c.getLayer()))
                .thenComparing(c -> -level(mastery, c.getId()));
    }

    /** 铁律 3：arity 强制驱动 */
    private int decideArity(Concept primary, int primaryLevel, int candidateCount) {
        if (candidateCount == 0) {
            return 1;                       // 没有合法伙伴，老老实实单点
        }
        Random rng = new Random(seed(primary.getId(), primaryLevel));

        boolean forceCombine = primaryLevel >= ANCHOR_LEVEL;   // 单点已达标 -> 必须变形
        boolean combine = forceCombine || rng.nextDouble() < COMBINE_RATIO;
        if (!combine) {
            return 1;
        }
        if (candidateCount >= 2 && rng.nextDouble() < TRIPLE_RATIO) {
            return 3;
        }
        return 2;
    }

    private long seed(Long conceptId, int level) {
        return conceptId * 1_000_003L + level * 31L;
    }

    private int level(Map<Long, Mastery> mastery, Long conceptId) {
        Mastery m = mastery.get(conceptId);
        return m == null ? 0 : m.getMasteryLevel();
    }
}
