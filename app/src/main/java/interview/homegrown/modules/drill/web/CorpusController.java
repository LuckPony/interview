package interview.homegrown.modules.drill.web;

import interview.homegrown.modules.drill.domain.Corpus;
import interview.homegrown.modules.drill.domain.CorpusChunk;
import interview.homegrown.modules.drill.repository.CorpusChunkRepository;
import interview.homegrown.modules.drill.repository.CorpusRepository;
import interview.homegrown.modules.drill.service.CorpusService;
import interview.homegrown.modules.drill.web.dto.CorpusView;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/** 个人资料上传：POST /api/corpus/upload（multipart）。解析后的文本存库，返回 id。 */
@RestController
@RequestMapping("/api/corpus")
public class CorpusController {

    private final CorpusService service;
    private final CorpusRepository corpusRepo;
    private final CorpusChunkRepository chunkRepo;

    public CorpusController(CorpusService service, CorpusRepository corpusRepo,
                            CorpusChunkRepository chunkRepo) {
        this.service = service;
        this.corpusRepo = corpusRepo;
        this.chunkRepo = chunkRepo;
    }

    @PostMapping("/upload")
    public CorpusView upload(@RequestParam("file") MultipartFile file) {
        Corpus c = service.upload(file, currentUserId());
        return toView(c);
    }

    /** 桌面端免上传：直接读本地文件 / 文件夹（仅本地部署有意义）。 */
    @PostMapping("/from-path")
    public CorpusView fromPath(@RequestBody FromPathRequest req) {
        Corpus c = service.fromPath(req.path(), currentUserId());
        return toView(c);
    }

    /** 云端桌面端：Electron 在本机读好文件字节传上来，服务端 Tika 解析合并。 */
    @PostMapping("/from-files")
    public CorpusView fromFiles(@RequestParam("files") MultipartFile[] files,
                                @RequestParam(value = "folderName", required = false) String folderName) {
        Corpus c = service.fromFiles(files, folderName, currentUserId());
        return toView(c);
    }

    @GetMapping
    public List<CorpusView> list() {
        return service.list(currentUserId()).stream().map(this::toView).toList();
    }

    @DeleteMapping("/{corpusId}")
    public DeleteResult delete(@PathVariable Long corpusId) {
        service.delete(corpusId, currentUserId());
        return new DeleteResult(true);
    }

    /**
     * 资料候选知识点（C1）：摄取后异步拆块 + LLM 标注完成时返回「知识点清单 + 每知识点命中的块摘要」，
     * 供建计划页展示、由用户确认。未完成索引时 indexed=false，前端显示「资料处理中…」。
     */
    @GetMapping("/{corpusId}/knowledge-points")
    public KnowledgePointsView knowledgePoints(@PathVariable Long corpusId) {
        Long uid = currentUserId();
        Corpus corpus = corpusRepo.findById(corpusId)
                .filter(c -> c.getUserId().equals(uid))
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "资料不存在"));
        List<CorpusChunk> chunks = chunkRepo.findByCorpusIdOrderBySeqAsc(corpusId);
        if (chunks.isEmpty()) {
            return new KnowledgePointsView(false, List.of());
        }
        // 按 topic 分组（topic 为 null 的归入「未标注」），每知识点取前 3 条块摘要
        Map<String, List<CorpusChunk>> byTopic = new LinkedHashMap<>();
        for (CorpusChunk c : chunks) {
            String key = (c.getTopic() == null || c.getTopic().isBlank()) ? "（未标注）" : c.getTopic().trim();
            byTopic.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
        }
        List<KnowledgePointsView.PointItem> points = byTopic.entrySet().stream()
                .map(e -> new KnowledgePointsView.PointItem(e.getKey(), e.getValue().size(),
                        e.getValue().stream()
                                .limit(3)
                                .map(c -> c.getSummary() == null || c.getSummary().isBlank()
                                        ? c.getTitle() : c.getSummary())
                                .toList()))
                .toList();
        return new KnowledgePointsView(true, points);
    }

    public record KnowledgePointsView(boolean indexed, List<PointItem> points) {
        public record PointItem(String name, int chunkCount, List<String> snippets) {
        }
    }

    private CorpusView toView(Corpus corpus) {
        return new CorpusView(
                corpus.getId(),
                corpus.getName(),
                corpus.getCharCount(),
                corpus.getSourceType(),
                corpus.getCreatedAt()
        );
    }

    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Long)) {
            throw new ResponseStatusException(UNAUTHORIZED, "未鉴权");
        }
        return (Long) auth.getPrincipal();
    }

    public record FromPathRequest(String path) {}

    public record DeleteResult(boolean ok) {}
}
