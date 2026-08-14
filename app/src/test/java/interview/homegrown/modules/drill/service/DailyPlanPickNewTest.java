package interview.homegrown.modules.drill.service;

import interview.homegrown.modules.drill.domain.Concept;
import interview.homegrown.modules.drill.domain.Mastery;
import interview.homegrown.modules.drill.repository.ConceptRepository;
import interview.homegrown.modules.drill.repository.MasteryRepository;
import interview.homegrown.modules.drill.service.DailyPlanService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * pickNew 的「层掌握率递进解锁 + 等概率抽层」逻辑验证。
 *
 * <p>规则：层 L 可出 ⇔ 它之下所有存在的层掌握率 >= 50%（掌握率 = 写达标 mastery_level>=2 概念数 / 该层概念总数）；
 * 每个名额独立等概率抽层；层内按 id 顺序取未掌握概念；无未掌握概念的层不参与。
 */
class DailyPlanPickNewTest {

    private final ConceptRepository conceptRepo = mock(ConceptRepository.class);
    private final MasteryRepository masteryRepo = mock(MasteryRepository.class);

    private DailyPlanService service() {
        // 只用到 conceptRepo / masteryRepo，其余依赖传 null
        return new DailyPlanService(null, null, conceptRepo, masteryRepo,
                null, null, null, null);
    }

    private Concept c(long id, int layer) {
        Concept c = new Concept();
        c.setId(id);
        c.setLayer(layer);
        return c;
    }

    private Mastery m(long conceptId, int level) {
        Mastery m = new Mastery();
        m.setConceptId(conceptId);
        m.setMasteryLevel(level);
        return m;
    }

    private void stubPlan(List<Concept> concepts, List<Mastery> mastery) {
        when(conceptRepo.findByStudyPlanId(1L)).thenReturn(concepts);
        when(masteryRepo.findByUserId(anyLong())).thenReturn(mastery);
    }

    @Test
    void 一层未掌握时只出L1() {
        stubPlan(List.of(c(1, 1), c(2, 1), c(3, 2), c(4, 2), c(5, 3)), List.of());
        List<Concept> out = service().pickNew(1L, 1L, 3);
        assertEquals(2, out.size(), "L1 池只有 2 个，取满即止");
        assertTrue(out.stream().allMatch(x -> x.getLayer() == 1), "未掌握时只出 L1：" + out);
    }

    @Test
    void L1达标50时解锁L2但L3仍锁() {
        // L1: 4 个，2 个写达标 → 50%；L2: 2 个未掌握 → 0%；L3: 1 个未掌握
        List<Concept> concepts = List.of(c(1, 1), c(2, 1), c(3, 1), c(4, 1), c(5, 2), c(6, 2), c(7, 3));
        stubPlan(concepts, List.of(m(1, 2), m(2, 2)));
        List<Concept> out = service().pickNew(1L, 1L, 3);
        assertEquals(3, out.size());
        assertTrue(out.stream().allMatch(x -> x.getLayer() <= 2), "L3 必须被锁住：" + out);
        // 换 200 个 seed（不同 userId），L2 必然出现、L3 一次都不该出现
        boolean sawL2 = false;
        for (long uid = 1; uid <= 200; uid++) {
            for (Concept c : service().pickNew(uid, 1L, 3)) {
                assertTrue(c.getLayer() <= 2, "L3 不该被解锁");
                if (c.getLayer() == 2) sawL2 = true;
            }
        }
        assertTrue(sawL2, "L2 解锁后应能出到 L2 的题");
    }

    @Test
    void L1L2均达标50时三层等概率() {
        // L1: 4 个，2 个达标；L2: 2 个，1 个达标；L3: 1 个未掌握
        List<Concept> concepts = List.of(c(1, 1), c(2, 1), c(3, 1), c(4, 1), c(5, 2), c(6, 2), c(7, 3));
        stubPlan(concepts, List.of(m(1, 2), m(2, 2), m(5, 2)));
        List<Concept> out = service().pickNew(1L, 1L, 3);
        assertEquals(3, out.size());
        assertTrue(out.stream().allMatch(x -> x.getLayer() <= 3));

        // 统计 600 次抽层：L1/L2 各约一半、L3 也应出现
        int[] count = new int[4];
        for (long uid = 1; uid <= 200; uid++) {
            for (Concept c : service().pickNew(uid, 1L, 3)) {
                count[c.getLayer()]++;
            }
        }
        int total = count[1] + count[2] + count[3];
        assertEquals(600, total);
        assertTrue(count[3] > 0, "L3 解锁后应能出到 L3 的题");
        double l1Ratio = (double) count[1] / total;
        assertTrue(l1Ratio > 0.35 && l1Ratio < 0.65,
                "L1 占比应接近 1/3（等概率三层），实际 " + l1Ratio);
    }

    @Test
    void L1全掌握时只出L2() {
        List<Concept> concepts = List.of(c(1, 1), c(2, 1), c(3, 2), c(4, 2));
        stubPlan(concepts, List.of(m(1, 2), m(2, 2)));
        List<Concept> out = service().pickNew(1L, 1L, 3);
        assertEquals(2, out.size(), "L2 池只有 2 个");
        assertTrue(out.stream().allMatch(x -> x.getLayer() == 2), "L1 已无未掌握概念，只出 L2：" + out);
    }

    @Test
    void 可出池小于cap时不强凑() {
        stubPlan(List.of(c(1, 1)), List.of());
        List<Concept> out = service().pickNew(1L, 1L, 3);
        assertEquals(1, out.size(), "池只有 1 个就出 1 个");
    }

    @Test
    void 空计划返回空() {
        stubPlan(List.of(), List.of());
        assertTrue(service().pickNew(1L, 1L, 3).isEmpty());
    }
}
