package interview.homegrown.modules.drill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.homegrown.common.ai.StructuredOutputInvoker;
import interview.homegrown.modules.drill.domain.Corpus;
import interview.homegrown.modules.drill.domain.CorpusChunk;
import interview.homegrown.modules.drill.repository.CorpusChunkRepository;
import interview.homegrown.modules.drill.repository.CorpusRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import jakarta.annotation.PreDestroy;
import interview.homegrown.common.ai.AiSettingsService;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 资料结构化索引：把一篇资料拆成「逻辑主题块」+ LLM 标注（块标题 / 对应知识点 / 摘要）+ 资料总览。
 *
 * <p>切块策略（服务端启发式，保证<b>原文零改动、零丢失</b>）：
 * <ul>
 *   <li>以 Markdown 标题 / 数字章节行 / 「第X章」等标题行为块边界；无标题文本按段落聚合；</li>
 *   <li>软上限 6000 字（尽量在段落边界收尾），长段落每 12000 字继续切块，
 *       不丢弃后文；块总数上限 30（超出合并进最后一块）；</li>
 *   <li>LLM 只负责标注（每块 title / topic 对应知识点名 / summary + 资料总览 overview），
 *       不重写原文——避免大资料被模型截断/篡改。</li>
 * </ul>
 *
 * <p>触发：资料摄取（upload / fromPath / fromFiles）保存后异步执行，不阻塞请求；
 * 幂等：已有块则跳过。LLM 标注失败时回退「标题=块首行、无 topic/summary」，
 * 出题/对话仍能按块注入（只是检索精度降级，不阻塞）。
 */
@Component
public class CorpusIndexer {

    private static final Logger log = LoggerFactory.getLogger(CorpusIndexer.class);

    /** 块软上限（字符）：到段落边界收尾；无边界时放宽到硬上限 */
    static final int SOFT_MAX_CHARS = 6000;
    /** 长段落切分阈值；不丢弃剩余文本。 */
    static final int HARD_MAX_CHARS = 12000;
    /** 块总数上限：超出合并进最后一块 */
    static final int MAX_CHUNKS = 30;

    private final CorpusRepository corpusRepo;
    private final CorpusChunkRepository chunkRepo;
    private final StructuredOutputInvoker invoker;
    private final ObjectMapper objectMapper;
    private final AiSettingsService settings;
    private final TransactionTemplate transactions;
    private final Set<Long> running = ConcurrentHashMap.newKeySet();
    private final ExecutorService pool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(64), r -> {
        Thread t = new Thread(r, "corpus-indexer");
        t.setDaemon(true);
        return t;
    }, new ThreadPoolExecutor.AbortPolicy());

    public CorpusIndexer(CorpusRepository corpusRepo, CorpusChunkRepository chunkRepo,
                         StructuredOutputInvoker invoker, ObjectMapper objectMapper,
                         AiSettingsService settings, TransactionTemplate transactions) {
        this.corpusRepo = corpusRepo;
        this.chunkRepo = chunkRepo;
        this.invoker = invoker;
        this.objectMapper = objectMapper;
        this.settings = settings;
        this.transactions = transactions;
    }

    /** 异步触发索引（摄取后调用，不阻塞请求）。幂等：已有块跳过。 */
    public void indexAsync(Long corpusId) {
        indexAsync(corpusId, false);
    }

    public void indexAsync(Long corpusId, boolean refresh) {
        if (corpusId == null || !running.add(corpusId)) return;
        var snapshot = settings.currentProviderForRequest();
        try {
          pool.submit(() -> settings.withTaskConfig(snapshot, () -> {
            try {
                index(corpusId, refresh);
            } catch (Exception e) {
                log.warn("资料索引失败 (corpusId={})", corpusId, e);
                updateState(corpusId, "FAILED");
            } finally {
                running.remove(corpusId);
            }
          }));
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            running.remove(corpusId);
            updateState(corpusId, "FAILED");
            log.warn("资料索引队列繁忙，用户可稍后重试: corpusId={}", corpusId);
        }
    }

    @PreDestroy
    void shutdown() { pool.shutdownNow(); }

    private void updateState(Long id, String state) {
        transactions.executeWithoutResult(status -> corpusRepo.findLockedById(id).ifPresent(c -> {
            c.setIndexState(state); corpusRepo.save(c);
        }));
    }

    /** 同步索引：切块 + LLM 标注 + 存库。幂等：已有块跳过。 */
    public synchronized void index(Long corpusId) {
        index(corpusId, false);
    }

    void index(Long corpusId, boolean refresh) {
        if (corpusId == null) return;
        if (!refresh && chunkRepo.countByCorpusId(corpusId) > 0) return;
        Corpus corpus = corpusRepo.findById(corpusId).orElse(null);
        if (corpus == null || corpus.getText() == null || corpus.getText().isBlank()) return;

        updateState(corpusId, "RUNNING");
        List<CorpusChunk> existing = chunkRepo.findByCorpusIdOrderBySeqAsc(corpusId);
        List<CorpusChunk> raw = existing;
        if (raw.isEmpty()) {
            raw = new ArrayList<>();
            for (Chunk part : split(corpus.getText())) {
                CorpusChunk chunk = new CorpusChunk();
                chunk.setSeq(raw.size()); chunk.setTitle(part.heading()); chunk.setText(part.text());
                raw.add(chunk);
            }
        }
        var sections = CorpusOutline.sections(raw);
        List<Chunk> chunks = sections.stream().map(s -> new Chunk(s.title(), s.text())).toList();
        if (chunks.isEmpty()) return;

        IndexOutput out = annotate(corpus, chunks);
        String overview = out != null && out.overview() != null && !out.overview().isBlank()
                ? out.overview() : corpus.getOverview() != null && !corpus.getOverview().isBlank() ? corpus.getOverview()
                : "内容摘录：" + corpus.getText().substring(0, Math.min(180, corpus.getText().length()));
        transactions.executeWithoutResult(status -> {
        Corpus current = corpusRepo.findLockedById(corpusId).orElse(null);
        if (current == null) return; // 用户在模型标注期间删除了资料，不写回孤儿数据。
        var entities = new ArrayList<CorpusChunk>();
        int seq = 0;
        for (CorpusOutline.Section section : sections) {
            IndexOutput.ChunkMeta meta = findMeta(out, seq);
            String title = CorpusOutline.label(meta == null ? null : meta.title());
            String summary = meta == null ? section.summary() : meta.summary();
            for (CorpusChunk e : section.chunks()) {
                // 不重建已有行，保留 concept_chunk 外键及历史工具链接。
                e.setCorpusId(corpusId);
                e.setTitle(title.isBlank() ? section.title() : title);
                e.setTopic(meta == null ? section.topic() : CorpusOutline.label(meta.topic()));
                e.setSummary(summary);
                e.setCharCount(e.getText().length());
                entities.add(e);
            }
            seq++;
        }
        chunkRepo.saveAll(entities);
        current.setOverview(overview);
        current.setIndexState(out == null ? "BASIC" : "READY");
        corpusRepo.save(current);
        });
        log.info("资料索引完成: corpusId={}, chunks={}, overview={}字", corpusId, chunks.size(),
                overview == null ? 0 : overview.length());
    }

    /** 候选知识点清单（供建计划时展示给用户确认）：块 topic 去重。 */
    public List<String> candidateTopics(Long corpusId) {
        return CorpusOutline.labels(CorpusOutline.sections(chunkRepo.findByCorpusIdOrderBySeqAsc(corpusId)).stream()
                .filter(s -> !s.referenceOnly()).map(CorpusOutline.Section::name).toList());
    }

    // ------------------------------------------------------------ 切块

    record Chunk(String heading, String text) {
    }

    /** 启发式切块：标题行为新块起点；无标题文本按段落聚合（软上限 6000，段落边界收尾）。
     *  切完若块数超 {@link #MAX_CHUNKS}，尾部块合并进第 MAX_CHUNKS 块（内容不丢）。 */
    static List<Chunk> split(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<Chunk> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        String curHeading = "";
        String fence = null;

        for (String line : text.split("\n")) {
            String stripped = line.stripLeading();
            boolean fenceLine = stripped.startsWith("```") || stripped.startsWith("~~~");
            boolean inCode = fence != null;
            if (fenceLine) {
                String marker = stripped.substring(0, 3);
                if (fence == null) fence = marker;
                else if (fence.equals(marker)) fence = null;
            }
            if (!inCode && !fenceLine && CorpusOutline.heading(line)) {
                // 标题行：关闭当前块（若有），作为新块起点
                if (cur.length() > 0) {
                    out.add(new Chunk(curHeading, cur.toString().trim()));
                    cur = new StringBuilder();
                }
                curHeading = line;
                cur.append(line);
                continue;
            }
            if (cur.length() > 0) cur.append('\n');
            cur.append(line);

            // 软上限：到段落边界（空行）收尾；找不到边界就继续到硬上限
            if (line.isBlank() && cur.length() >= SOFT_MAX_CHARS) {
                out.add(new Chunk(curHeading, cur.toString().trim()));
                cur = new StringBuilder();
                curHeading = "";
            }
        }
        if (cur.length() > 0) {
            out.add(new Chunk(curHeading, cur.toString().trim()));
        }

        // 超长块拆分而不是丢弃尾部；知识库索引不能漏掉后半篇资料。
        List<Chunk> bounded = new ArrayList<>();
        for (int i = 0; i < out.size(); i++) {
            Chunk c = out.get(i);
            for (int start = 0; start < c.text().length(); start += HARD_MAX_CHARS) {
                bounded.add(new Chunk(c.heading(), c.text().substring(start, Math.min(start + HARD_MAX_CHARS, c.text().length()))));
            }
        }
        out = bounded;

        // 块数超限：尾部块全部合并进第 MAX_CHUNKS 块（内容不丢，只是粒度变粗）
        if (out.size() > MAX_CHUNKS) {
            Chunk last = out.get(MAX_CHUNKS - 1);
            StringBuilder merged = new StringBuilder(last.text());
            for (int i = MAX_CHUNKS; i < out.size(); i++) {
                merged.append("\n\n").append(out.get(i).text());
            }
            List<Chunk> trimmed = new ArrayList<>(out.subList(0, MAX_CHUNKS - 1));
            trimmed.add(new Chunk(last.heading(), merged.toString()));
            out = trimmed;
        }
        return out.stream().filter(c -> !c.text().isBlank()).toList();
    }

    // ------------------------------------------------------------ LLM 标注

    private static final String ANNOTATE_SYSTEM = """
            你是资料整理助手。下面给出按顺序整理的目录章节（长章节含首中尾节选），
            请你：
            1. overview：用 150 字内概括这份资料讲什么、适合什么人学什么；
            2. chunks：为每一块给出 seq（与输入块编号一致，从 0 开始）、title（块标题）、topic（这块内容对应的"知识点名"，要像
               学习计划里的知识点那样简短、可独立命名，如「线程池」「volatile 语义」；
               一块可以没有知识点则 topic 填 null）、summary（这块内容 60 字内的摘要）。
            只依据给定的预览内容标注，不要臆造块里没有的内容。严格遵循格式说明的 JSON。
            """;

    /** LLM 标注每块 + 总览；失败返回 null（调用方回退默认标题）。 */
    private IndexOutput annotate(Corpus corpus, List<Chunk> chunks) {
        StringBuilder preview = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            Chunk c = chunks.get(i);
            String body = CorpusLibraryService.sample(c.text(), 1200);
            preview.append("【块 ").append(i).append("】").append(c.heading()).append('\n')
                    .append(body).append("\n\n");
        }
        String user = "资料名：《" + corpus.getName() + "》\n\n" + preview;
        try {
            return invoker.invoke(ANNOTATE_SYSTEM, user, IndexOutput.class);
        } catch (Exception e) {
            log.warn("资料标注失败（回退默认标题）: {}", e.getMessage());
            return null;
        }
    }

    private IndexOutput.ChunkMeta findMeta(IndexOutput out, int seq) {
        if (out == null || out.chunks() == null) return null;
        for (IndexOutput.ChunkMeta m : out.chunks()) {
            if (m != null && m.seq() == seq) return m;
        }
        return null;
    }

    // ------------------------------------------------------------ DTO

    public record IndexOutput(String overview, List<ChunkMeta> chunks) {
        public record ChunkMeta(int seq, String title, String topic, String summary) {
        }
    }
}
