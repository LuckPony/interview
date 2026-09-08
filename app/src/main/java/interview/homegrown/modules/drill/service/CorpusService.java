package interview.homegrown.modules.drill.service;

import interview.homegrown.common.exception.BusinessException;
import interview.homegrown.common.exception.ErrorCode;
import interview.homegrown.modules.drill.ai.FileParser;
import interview.homegrown.modules.drill.domain.Concept;
import interview.homegrown.modules.drill.domain.Corpus;
import interview.homegrown.modules.drill.domain.StudyPlan;
import interview.homegrown.modules.drill.repository.ConceptRepository;
import interview.homegrown.modules.drill.repository.CorpusRepository;
import interview.homegrown.modules.drill.repository.StudyPlanRepository;
import interview.homegrown.modules.interview.repository.InterviewSessionRepository;
import interview.homegrown.infrastructure.file.FileStorageService;
import interview.homegrown.infrastructure.file.DocumentParseService;
import interview.homegrown.common.ai.AiSettingsService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 个人资料 Corpus 的摄取与检索。
 *
 * <p>规划和面试通过 CorpusLibraryService 复用章节索引与有界原文节选，
 * 不在此重复建设向量库。旧概念生成入口保留 {@link #MAX_INJECT_CHARS} 上限。
 *
 * <p>两种摄取：{@link #upload} 收浏览器上传的字节；{@link #fromPath} 直接读本地
 * 文件 / 文件夹（桌面端用，免上传、解大项目痛点）。fromPath 仅限本地部署，
 * 且做了系统目录 deny-list + 构建产物目录跳过，避免把 /System 或 node_modules 拖进来。
 */
@Service
public class CorpusService {

    /** 注入 prompt 的字符上限。 */
    static final int MAX_INJECT_CHARS = 20000;

    /** from-path 合并后的字符上限（大项目一次性吃下时截断）。 */
    static final int MAX_PATH_CHARS = 200_000;

    /** 单文件超过此大小（字节）直接跳过，防 OOM。 */
    static final long MAX_FILE_BYTES = 20L * 1024 * 1024;

    private static final Set<String> SUPPORTED_EXT =
            Set.of("pdf", "txt", "md", "markdown", "mdx", "docx");

    /** 出于安全与性能，直接拒绝这些系统根目录（含其子孙）。仅本地部署，防误扫系统盘。 */
    private static final Set<String> SYSTEM_ROOTS = Set.of(
            "/System", "/usr", "/bin", "/sbin", "/etc",
            "/private/var", "/private/etc", "/Library", "/Applications",
            "C:\\Windows", "C:\\Program Files", "C:\\ProgramData");

    /** 遍历时跳过的目录名（构建产物 / 版本控制 / 依赖），避免把大项目拖爆。 */
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", "node_modules", "target", "build", "dist", "out",
            ".next", "coverage", ".idea", ".vscode", "__pycache__");

    private final CorpusRepository corpusRepo;
    private final StudyPlanRepository planRepo;
    private final ConceptRepository conceptRepo;
    private final FileParser parser;
    private final CorpusIndexer indexer;
    private final CorpusLibraryService library;
    private final InterviewSessionRepository interviews;
    private final FileStorageService storage;
    private final DocumentParseService documentParser;
    private final AiSettingsService settings;

    public CorpusService(CorpusRepository corpusRepo, StudyPlanRepository planRepo,
                         ConceptRepository conceptRepo, FileParser parser, CorpusIndexer indexer,
                         CorpusLibraryService library, InterviewSessionRepository interviews,
                         FileStorageService storage, DocumentParseService documentParser, AiSettingsService settings) {
        this.corpusRepo = corpusRepo;
        this.planRepo = planRepo;
        this.conceptRepo = conceptRepo;
        this.parser = parser;
        this.indexer = indexer;
        this.library = library; this.interviews = interviews; this.storage = storage;
        this.documentParser = documentParser; this.settings = settings;
    }

    @Transactional(readOnly = true)
    public List<Corpus> list(Long userId) {
        return corpusRepo.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional
    public void delete(Long corpusId, Long userId) {
        Corpus corpus = corpusRepo.findLockedById(corpusId)
                .filter(item -> item.getUserId().equals(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND));
        if (planRepo.existsByUserIdAndCorpusId(userId, corpusId) || interviews.existsByUserIdAndCorpusId(userId, corpusId)) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "该资料正在被学习计划或面试记录引用，请先处理关联记录"
            );
        }
        corpusRepo.delete(corpus);
        String originalKey = corpus.getOriginalKey();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { storage.delete(originalKey); }
        });
    }

    public Corpus upload(MultipartFile file, Long userId) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请选择一个文件再上传");
        }
        String name = file.getOriginalFilename() == null ? "资料.txt" : file.getOriginalFilename().replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "_");
        if (name.length() > 180 || !isSupportedName(name)) throw new BusinessException(ErrorCode.FILE_TYPE_NOT_SUPPORTED, "支持 PDF、Word、TXT、Markdown，文件名不超过 180 字符");
        if (file.getSize() > MAX_FILE_BYTES) throw new BusinessException(ErrorCode.FILE_TOO_LARGE, "单份资料最大 20 MB");
        String text;
        byte[] bytes;
        String mime;
        try {
            bytes = file.getBytes();
            mime = documentParser.detectContectType(bytes);
            text = documentParser.parseTextPreservingLayout(bytes, name, MAX_PATH_CHARS);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.FILE_PARSE_FAILED, "读取上传文件失败");
        }
        if (text.isBlank()) throw new BusinessException(ErrorCode.FILE_PARSE_FAILED, "未提取到文字，扫描件请先添加文字层");
        Corpus c = new Corpus();
        c.setUserId(userId);
        c.setName(name);
        c.setSourceType("UPLOAD");
        c.setText(text);
        c.setCharCount(text.length());
        c.setOriginalKey(storage.upload(bytes, name, mime));
        c.setOriginalType(mime);
        Corpus saved;
        try {
            saved = corpusRepo.save(c);
        } catch (Exception e) {
            storage.delete(c.getOriginalKey());
            throw e;
        }
        // 异步拆块 + 知识点标注（不阻塞上传请求）
        indexer.indexAsync(saved.getId());
        return saved;
    }

    /**
     * 直接读本地文件 / 文件夹（桌面端免上传，解「大项目上传会爆」的痛点）。
     *
     * <p>流程：toRealPath 规范化（消解 ../ 与符号链接）→ deny-list 拦系统目录 →
     * Files.walk 过滤扩展名 + 跳过构建产物目录 → 逐文件 Tika 解析合并 → 截断后存库。
     * 单文件超 {@link #MAX_FILE_BYTES} 跳过，遍历出错不致命（跳过一个文件继续）。
     */
    public Corpus fromPath(String path, Long userId) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("请提供本地文件或文件夹路径");
        }
        Path root;
        try {
            root = Paths.get(path).toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            throw new IllegalArgumentException("路径无法访问或不存在：" + path);
        }
        if (!Files.exists(root)) {
            throw new IllegalArgumentException("路径不存在：" + path);
        }
        assertNotSystemDir(root);

        StringBuilder sb = new StringBuilder();
        int fileCount = 0;
        try (Stream<Path> stream = Files.walk(root)) {
            for (Iterator<Path> it = stream.iterator(); it.hasNext(); ) {
                Path p = it.next();
                if (!Files.isRegularFile(p)) continue;
                if (isSkippedDir(p)) continue;
                if (!isSupported(p)) continue;
                try {
                    if (Files.size(p) > MAX_FILE_BYTES) continue;
                    String text = parser.parse(Files.newInputStream(p), p.getFileName().toString());
                    if (text.isBlank()) continue;
                    sb.append("\n\n## ").append(p.getFileName()).append("\n").append(text);
                    fileCount++;
                    if (sb.length() > MAX_PATH_CHARS) break;
                } catch (IOException e) {
                    // 单个文件解析失败不致命，跳过
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("遍历路径失败：" + e.getMessage());
        }

        return persistMerged(root.getFileName() != null ? root.getFileName().toString() : path,
                "LOCAL_PATH", sb, fileCount, userId);
    }

    /**
     * 云端模式：后端在服务器上读不到用户本机路径，改由 Electron 在本机把支持的文件
     * 读成字节传上来，这里统一 Tika 解析合并。与 {@link #fromPath} 共用解析/截断/存库逻辑。
     */
    public Corpus fromFiles(MultipartFile[] files, String folderName, Long userId) {
        if (files == null || files.length == 0) {
            throw new IllegalArgumentException("请选择文件");
        }
        StringBuilder sb = new StringBuilder();
        int fileCount = 0;
        for (MultipartFile f : files) {
            if (f == null || f.isEmpty()) continue;
            String name = f.getOriginalFilename();
            if (name == null || !isSupportedName(name)) continue;
            if (f.getSize() > MAX_FILE_BYTES) continue;
            try {
                String text = parser.parse(f.getInputStream(), name);
                if (text.isBlank()) continue;
                sb.append("\n\n## ").append(name).append("\n").append(text);
                fileCount++;
                if (sb.length() > MAX_PATH_CHARS) break;
            } catch (IOException e) {
                // 单个文件解析失败不致命，跳过
            }
        }
        return persistMerged(folderName != null && !folderName.isBlank() ? folderName : "本地资料",
                "LOCAL_FILES", sb, fileCount, userId);
    }

    /** 合并解析结果 → 截断 → 存库（fromPath / fromFiles 共用）。 */
    private Corpus persistMerged(String name, String sourceType, StringBuilder sb,
                                 int fileCount, Long userId) {
        if (fileCount == 0 || sb.isEmpty()) {
            throw new IllegalArgumentException(
                    "没有可解析的资料（支持 pdf / txt / md / docx，且需带文字层）。");
        }
        String merged = sb.toString().trim();
        if (merged.length() > MAX_PATH_CHARS) {
            merged = merged.substring(0, MAX_PATH_CHARS)
                    + "\n…（资料较长，已截断到前 " + MAX_PATH_CHARS + " 字）";
        }
        Corpus c = new Corpus();
        c.setUserId(userId);
        c.setName(name);
        c.setSourceType(sourceType);
        c.setText(merged);
        c.setCharCount(merged.length());
        Corpus saved = corpusRepo.save(c);
        indexer.indexAsync(saved.getId());
        return saved;
    }

    private boolean isSkippedDir(Path p) {
        for (Path part : p) {
            if (SKIP_DIRS.contains(part.toString())) return true;
        }
        return false;
    }

    private boolean isSupported(Path p) {
        return isSupportedName(p.getFileName().toString());
    }

    private boolean isSupportedName(String fileName) {
        String name = fileName.toLowerCase();
        int dot = name.lastIndexOf('.');
        return dot >= 0 && SUPPORTED_EXT.contains(name.substring(dot + 1));
    }

    private void assertNotSystemDir(Path real) {
        for (String sys : SYSTEM_ROOTS) {
            if (real.startsWith(Paths.get(sys))) {
                throw new IllegalArgumentException("出于安全考虑，不能读取系统目录：" + real);
            }
        }
    }

    /** intake 阶段方向还没建，直接按 corpusId 取「《文件名》\n文本」。无则返回 null。 */
    public String referenceWithName(Long corpusId) {
        return library.reference(corpusId, settings.currentUserId());
    }

    public void requireOwned(Long corpusId, Long userId) {
        if (corpusId != null) library.requireOwned(corpusId, userId);
    }

    /** 按概念取其所属方向的资料文本（出题时注入）。无绑定则返回 null。 */
    public String referenceForConcept(Long conceptId) {
        Concept c = conceptRepo.findById(conceptId).orElse(null);
        if (c == null || c.getStudyPlanId() == null) return null;
        StudyPlan p = planRepo.findById(c.getStudyPlanId()).orElse(null);
        if (p == null || p.getCorpusId() == null) return null;
        Corpus corpus = corpusRepo.findById(p.getCorpusId()).orElse(null);
        if (corpus == null || corpus.getText() == null) return null;
        return truncate(corpus.getText());
    }

    private String truncate(String text) {
        if (text.length() <= MAX_INJECT_CHARS) return text;
        return text.substring(0, MAX_INJECT_CHARS)
                + "\n…（资料较长，已截断到前 " + MAX_INJECT_CHARS + " 字）";
    }
}
