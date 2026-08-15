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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ProgressContextService 组装验证：各来源缺失时优雅降级，命中时正确注入。
 */
class ProgressContextServiceTest {

    private final MasteryRepository masteryRepo = mock(MasteryRepository.class);
    private final ConceptRepository conceptRepo = mock(ConceptRepository.class);
    private final StudyPlanRepository planRepo = mock(StudyPlanRepository.class);
    private final CorpusRepository corpusRepo = mock(CorpusRepository.class);
    private final CorpusChunkRepository chunkRepo = mock(CorpusChunkRepository.class);
    private final ConceptChunkRepository conceptChunkRepo = mock(ConceptChunkRepository.class);
    private final WebContentRepository webRepo = mock(WebContentRepository.class);

    private ProgressContextService service() {
        return new ProgressContextService(masteryRepo, conceptRepo, planRepo, corpusRepo,
                chunkRepo, conceptChunkRepo, webRepo);
    }

    private Concept concept(long id, String name, int layer, Long planId) {
        Concept c = new Concept();
        c.setId(id);
        c.setName(name);
        c.setLayer(layer);
        c.setTopic("测试方向");
        c.setDescription("概念说明");
        c.setStudyPlanId(planId);
        return c;
    }

    @Test
    void 空概念列表返回null() {
        assertNull(service().contextFor(1L, List.of()));
        assertNull(service().contextFor(1L, (Long) null));
    }

    @Test
    void 无任何数据时仍返回概念骨架() {
        Concept c = concept(1L, "线程池", 2, null);
        when(conceptRepo.findById(1L)).thenReturn(Optional.of(c));
        when(masteryRepo.findByUserIdAndConceptId(1L, 1L)).thenReturn(Optional.empty());

        String ctx = service().contextFor(1L, List.of(1L));
        assertNotNull(ctx, "至少包含概念骨架");
        assertTrue(ctx.contains("线程池"), "骨架含概念名");
        assertTrue(ctx.contains("概念说明"), "骨架含说明");
    }

    @Test
    void 含画像掌握度与资料块() {
        Concept c = concept(1L, "volatile", 3, 10L);
        when(conceptRepo.findById(1L)).thenReturn(Optional.of(c));
        Mastery m = new Mastery();
        m.setConceptId(1L);
        m.setMasteryLevel(1);
        when(masteryRepo.findByUserIdAndConceptId(1L, 1L)).thenReturn(Optional.of(m));
        when(planRepo.findById(10L)).thenReturn(Optional.of(new StudyPlan() {{
            setTitle("Java 并发");
        }}));
        when(conceptChunkRepo.chunkIdsOfConcepts(List.of(1L))).thenReturn(List.of(7L));
        CorpusChunk chunk = new CorpusChunk();
        chunk.setId(7L);
        chunk.setSeq(1);
        chunk.setTitle("volatile 语义");
        chunk.setTopic("volatile");
        chunk.setSummary("内存可见性");
        chunk.setText("volatile 保证可见性，不保证原子性。");
        when(chunkRepo.findAllById(List.of(7L))).thenReturn(List.of(chunk));
        when(webRepo.findByConceptIdIn(List.of(1L))).thenReturn(List.of());
        when(conceptRepo.findByStudyPlanId(10L)).thenReturn(List.of(c));

        String ctx = service().contextFor(1L, List.of(1L));
        assertNotNull(ctx);
        assertTrue(ctx.contains("掌握度 1/3"), "画像含掌握度：" + ctx);
        assertTrue(ctx.contains("volatile 语义"), "资料块标题注入");
        assertTrue(ctx.contains("不保证原子性"), "资料块正文注入");
    }

    @Test
    void 索引未完成时回退整篇资料() {
        Concept c = concept(1L, "锁", 1, 10L);
        when(conceptRepo.findById(1L)).thenReturn(Optional.of(c));
        when(conceptChunkRepo.chunkIdsOfConcepts(List.of(1L))).thenReturn(List.of());
        StudyPlan plan = new StudyPlan();
        plan.setId(10L);
        plan.setCorpusId(5L);
        when(planRepo.findById(10L)).thenReturn(Optional.of(plan));
        Corpus corpus = new Corpus();
        corpus.setName("并发编程实践");
        corpus.setText("第一章 锁的基础……");
        when(corpusRepo.findById(5L)).thenReturn(Optional.of(corpus));

        String ctx = service().contextFor(1L, List.of(1L));
        assertNotNull(ctx);
        assertTrue(ctx.contains("并发编程实践"), "回退注入资料名");
        assertTrue(ctx.contains("第一章 锁的基础"), "回退注入资料正文");
    }

    @Test
    void 含互联网补充() {
        Concept c = concept(1L, "synchronized", 2, null);
        when(conceptRepo.findById(1L)).thenReturn(Optional.of(c));
        when(webRepo.findByConceptIdIn(List.of(1L))).thenReturn(List.of(new WebContent() {{
            setConceptId(1L);
            setTitle("synchronized");
            setText("synchronized 是 JVM 内置锁。");
        }}));

        String ctx = service().contextFor(1L, List.of(1L));
        assertNotNull(ctx);
        assertTrue(ctx.contains("互联网补充"), "互联网段存在");
        assertTrue(ctx.contains("JVM 内置锁"), "互联网内容注入");
    }

    @SuppressWarnings("unchecked")
    private void whenFindAllById(List<CorpusChunk> chunks) {
        when(chunkRepo.findAllById(any())).thenReturn(chunks);
    }
}
