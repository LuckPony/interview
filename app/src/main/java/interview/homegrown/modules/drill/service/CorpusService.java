package interview.homegrown.modules.drill.service;

import interview.homegrown.modules.drill.ai.FileParser;
import interview.homegrown.modules.drill.domain.Concept;
import interview.homegrown.modules.drill.domain.Corpus;
import interview.homegrown.modules.drill.domain.StudyPlan;
import interview.homegrown.modules.drill.repository.ConceptRepository;
import interview.homegrown.modules.drill.repository.CorpusRepository;
import interview.homegrown.modules.drill.repository.StudyPlanRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 个人资料 Corpus 的摄取与检索。
 *
 * <p>v1 不做向量库（RAG）：资料不大时直接把解析文本注入 prompt（全文注入）。
 * 注入前用 {@link #MAX_INJECT_CHARS} 截断，避免撑爆 LLM 上下文窗口 + 烧 token。
 * 等真遇到"一本 800 页的书"塞不进窗口，再升级到切块 + pgvector 检索。
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

    public CorpusService(CorpusRepository corpusRepo, StudyPlanRepository planRepo,
                         ConceptRepository conceptRepo, FileParser parser) {
        this.corpusRepo = corpusRepo;
        this.planRepo = planRepo;
        this.conceptRepo = conceptRepo;
        this.parser = parser;
    }

    public Corpus upload(MultipartFile file, Long userId) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择一个文件再上传");
        }
        String text;
        try {
            text = parser.parse(file.getInputStream(), file.getOriginalFilename());
        } catch (IOException e) {
            throw new IllegalArgumentException("读取上传文件失败：" + e.getMessage());
        }
        Corpus c = new Corpus();
        c.setUserId(userId);
        c.setName(file.getOriginalFilename());
        c.setSourceType("UPLOAD");
        c.setText(text);
        c.setCharCount(text.length());
        return corpusRepo.save(c);
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

        if (fileCount == 0 || sb.length() == 0) {
            throw new IllegalArgumentException(
                    "在该路径下没找到可解析的资料（支持 pdf / txt / md / docx，且需带文字层）。");
        }
        String merged = sb.toString().trim();
        if (merged.length() > MAX_PATH_CHARS) {
            merged = merged.substring(0, MAX_PATH_CHARS)
                    + "\n…（资料较长，已截断到前 " + MAX_PATH_CHARS + " 字）";
        }

        Corpus c = new Corpus();
        c.setUserId(userId);
        c.setName(root.getFileName() != null ? root.getFileName().toString() : path);
        c.setSourceType("LOCAL_PATH");
        c.setText(merged);
        c.setCharCount(merged.length());
        return corpusRepo.save(c);
    }

    private boolean isSkippedDir(Path p) {
        for (Path part : p) {
            if (SKIP_DIRS.contains(part.toString())) return true;
        }
        return false;
    }

    private boolean isSupported(Path p) {
        String name = p.getFileName().toString().toLowerCase();
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
        if (corpusId == null) return null;
        Corpus c = corpusRepo.findById(corpusId).orElse(null);
        if (c == null || c.getText() == null) return null;
        return "《" + c.getName() + "》\n" + truncate(c.getText());
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
