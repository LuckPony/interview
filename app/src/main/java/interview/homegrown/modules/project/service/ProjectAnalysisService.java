package interview.homegrown.modules.project.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.homegrown.common.ai.StructuredOutputInvoker;
import interview.homegrown.modules.drill.domain.Concept;
import interview.homegrown.modules.drill.domain.StudyPlan;
import interview.homegrown.modules.drill.repository.ConceptRepository;
import interview.homegrown.modules.drill.repository.StudyPlanRepository;
import interview.homegrown.modules.drill.web.dto.PlanPoint;
import interview.homegrown.modules.drill.web.dto.PlanView;
import interview.homegrown.modules.drill.web.dto.StudyPlanDraft;
import interview.homegrown.modules.drill.service.StudyPlanService;
import interview.homegrown.modules.project.domain.ProjectDomain;
import interview.homegrown.modules.project.domain.ProjectImport;
import interview.homegrown.modules.project.domain.ProjectSubPoint;
import interview.homegrown.modules.project.repository.ProjectDomainRepository;
import interview.homegrown.modules.project.repository.ProjectImportRepository;
import interview.homegrown.modules.project.repository.ProjectSubPointRepository;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.springframework.http.HttpStatus.*;

/**
 * 项目导入与分析核心服务。
 * <p>流程：接收 zip/路径 → 骨架扫描（纯规则，快） → LLM 识别业务域（只读文件名） →
 * LLM 逐域分析代码（读真实内容） → 持久化域+子点 → 等待用户确认后创建学习计划。</p>
 * <p>LLM 分析使用当前用户配置的模型（通过 {@link StructuredOutputInvoker}，不硬编码）。</p>
 */
@Service
public class ProjectAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(ProjectAnalysisService.class);

    // ---------- 常量 ----------

    /** 单次域分析最多注入的代码字符数（防止撑爆 token）。 */
    static final int MAX_DOMAIN_CHARS = 12000;

    /** 并行分析域的线程池大小（LLM 并发过多会触发限流；3 是稳妥折中）。 */
    static final int PARALLEL_DOMAINS = 3;

    /** 骨架扫描时跳过的大目录/构建产物。 */
    static final Set<String> SKIP_DIRS = Set.of(
            ".git", "node_modules", "target", "build", "dist", "out",
            ".next", "coverage", ".idea", ".vscode", "__pycache__",
            ".gradle", "venv", ".venv", "env", ".env", "vendor");

    /** 骨架扫描时跳过的二进制/图片扩展名。 */
    static final Set<String> BINARY_EXTS = Set.of(
            ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".ico", ".svg",
            ".woff", ".woff2", ".ttf", ".eot",
            ".jar", ".war", ".zip", ".tar", ".gz", ".7z", ".rar",
            ".exe", ".dll", ".so", ".dylib",
            ".mp3", ".mp4", ".avi", ".mov", ".wav",
            ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx");

    // ---------- 依赖 ----------

    private final ProjectImportRepository importRepo;
    private final ProjectDomainRepository domainRepo;
    private final ProjectSubPointRepository subPointRepo;
    private final StudyPlanRepository planRepo;
    private final ConceptRepository conceptRepo;
    private final StudyPlanService studyPlanService;
    private final StructuredOutputInvoker invoker;
    private final ObjectMapper objectMapper;

    /** 域分析专用线程池（有界，避免 LLM 并发超限 / OOM）。 */
    private final Executor domainExecutor = Executors.newFixedThreadPool(PARALLEL_DOMAINS, r -> {
        Thread t = new Thread(r, "domain-analyzer");
        t.setDaemon(true);
        return t;
    });

    public ProjectAnalysisService(ProjectImportRepository importRepo,
                                  ProjectDomainRepository domainRepo,
                                  ProjectSubPointRepository subPointRepo,
                                  StudyPlanRepository planRepo,
                                  ConceptRepository conceptRepo,
                                  StudyPlanService studyPlanService,
                                  StructuredOutputInvoker invoker,
                                  ObjectMapper objectMapper) {
        this.importRepo = importRepo;
        this.domainRepo = domainRepo;
        this.subPointRepo = subPointRepo;
        this.planRepo = planRepo;
        this.conceptRepo = conceptRepo;
        this.studyPlanService = studyPlanService;
        this.invoker = invoker;
        this.objectMapper = objectMapper;
    }

    // ============================================================
    //  公开入口
    // ============================================================

    /**
     * 导入项目（上传 zip 字节）。返回 ProjectImport（状态 PENDING），
     * 异步开始分析，前端轮询 {@link #getStatus} 获取进度。
     */
    public ProjectImport importZip(MultipartFile file, Long userId) {
        String originalName = file.getOriginalFilename();
        String name = extractProjectName(originalName);
        // 同名项目只允许导入一次（幂等）
        Optional<ProjectImport> existing = importRepo.findByUserIdAndName(userId, name);
        if (existing.isPresent()) {
            throw new ResponseStatusException(CONFLICT, "项目「" + name + "」已导入，请先删除或换名");
        }

        Path tempDir;
        try {
            tempDir = Files.createTempDirectory("project_import_");
            unzip(file.getInputStream(), tempDir);
        } catch (IOException e) {
            throw new ResponseStatusException(BAD_REQUEST, "解压失败：" + e.getMessage());
        }

        ProjectImport pi = new ProjectImport();
        pi.setUserId(userId);
        pi.setName(name);
        pi.setRootPath(tempDir.toAbsolutePath().toString());
        pi.setStatus("PENDING");
        pi = importRepo.save(pi);

        final Long projectId = pi.getId();
        final Path root = tempDir;
        CompletableFuture.runAsync(() -> analyze(projectId, root, userId));
        return pi;
    }

    /**
     * 导入项目（本地路径，桌面端免上传）。同样异步分析。
     */
    public ProjectImport importPath(String path, Long userId) {
        Path root;
        try {
            root = Paths.get(path).toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            throw new ResponseStatusException(BAD_REQUEST, "路径无法访问：" + path);
        }
        if (!Files.exists(root)) {
            throw new ResponseStatusException(NOT_FOUND, "路径不存在：" + path);
        }

        String name = root.getFileName() != null ? root.getFileName().toString() : "项目";
        Optional<ProjectImport> existing = importRepo.findByUserIdAndName(userId, name);
        if (existing.isPresent()) {
            throw new ResponseStatusException(CONFLICT, "项目「" + name + "」已导入，请先删除或换名");
        }

        ProjectImport pi = new ProjectImport();
        pi.setUserId(userId);
        pi.setName(name);
        pi.setRootPath(root.toAbsolutePath().toString());
        pi.setStatus("PENDING");
        pi = importRepo.save(pi);

        final Long projectId = pi.getId();
        CompletableFuture.runAsync(() -> analyze(projectId, root, userId));
        return pi;
    }

    /**
     * 获取项目当前状态（含分析结果）。
     */
    public ProjectStatus getStatus(Long userId, Long projectId) {
        ProjectImport pi = importRepo.findByUserIdAndId(userId, projectId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "项目不存在"));
        return toStatus(pi);
    }

    /** 列出用户所有导入项目（含各自状态与分析结果）。 */
    public List<ProjectStatus> listForUser(Long userId) {
        return importRepo.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toStatus)
                .toList();
    }

    private ProjectStatus toStatus(ProjectImport pi) {
        List<ProjectDomain> domains = domainRepo.findByProjectIdOrderBySortOrderAsc(pi.getId());
        List<DomainView> domainViews = domains.stream().map(d -> {
            List<ProjectSubPoint> subs = subPointRepo.findByDomainIdOrderBySortOrderAsc(d.getId());
            return new DomainView(d.getId(), d.getName(), d.getOverview(),
                    parseStringList(d.getRefFiles()),
                    subs.stream().map(s -> new SubPointView(s.getId(), s.getName(), s.getDescription(),
                            parseStringList(s.getRefFiles()))).toList());
        }).toList();
        return new ProjectStatus(pi.getId(), pi.getName(), pi.getTechStack(),
                pi.getStatus(), pi.getErrorMsg(), domainViews);
    }

    /**
     * 把分析结果创建为学习计划（复用现有 StudyPlanService.confirm 逻辑）。
     * 通过 projectImportId 与 plan 关联，避免 title 被修改后反查失败。
     */
    @Transactional
    public PlanView createPlan(Long userId, Long projectId) {
        ProjectImport pi = importRepo.findByUserIdAndId(userId, projectId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "项目不存在"));
        if (!"READY".equals(pi.getStatus())) {
            throw new ResponseStatusException(BAD_REQUEST, "分析尚未完成，当前状态：" + pi.getStatus());
        }
        List<ProjectDomain> domains = domainRepo.findByProjectIdOrderBySortOrderAsc(projectId);
        if (domains.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "该项目没有分析出业务域，无法创建计划");
        }

        // 每个 domain 映射为 PlanPoint（知识点）
        List<PlanPoint> points = new ArrayList<>();
        for (ProjectDomain d : domains) {
            String note = d.getOverview() != null && d.getOverview().length() > 80
                    ? d.getOverview().substring(0, 80) + "…" : d.getOverview();
            points.add(new PlanPoint(d.getName(), 1, note));
        }

        // 构建 StudyPlanDraft 并确认
        StudyPlanDraft draft = new StudyPlanDraft(
                pi.getName() + " 项目源码",
                "深入理解项目「" + pi.getName() + "」的架构设计与实现",
                points, null);
        PlanView planView = studyPlanService.confirm(userId, draft);

        // confirm 创建的 plan 不带 projectImportId（draft 无此字段），这里按标题补上关联。
        // 标题是我们生成的非空标题，normalizeTitle 不会改动它，反查可靠。
        StudyPlan plan = planRepo.findByUserIdAndTitle(userId, draft.title()).orElse(null);
        if (plan != null && !projectId.equals(plan.getProjectImportId())) {
            plan.setProjectImportId(projectId);
            planRepo.save(plan);
        }
        if (plan == null) {
            throw new IllegalStateException("计划创建后未找到");
        }

        // 补写每个 concept 的 lesson_outline（子知识点拆解）
        for (ProjectDomain d : domains) {
            Concept concept = conceptRepo.findByStudyPlanIdAndName(plan.getId(), d.getName()).orElse(null);
            if (concept == null) continue;
            List<ProjectSubPoint> subs = subPointRepo.findByDomainIdOrderBySortOrderAsc(d.getId());
            List<String> subNames = subs.stream().map(ProjectSubPoint::getName).toList();
            try {
                concept.setLessonOutline(objectMapper.writeValueAsString(subNames));
                concept.setDescription(d.getOverview());
                conceptRepo.save(concept);
            } catch (JsonProcessingException e) {
                log.warn("序列化项目子点失败 (domain={}): {}", d.getName(), e.getMessage());
            }
        }

        return planView;
    }

    // ============================================================
    //  异步分析
    // ============================================================

    private void analyze(Long projectId, Path root, Long userId) {
        ProjectImport pi = importRepo.findById(projectId).orElse(null);
        if (pi == null) return;

        try {
            pi.setStatus("ANALYZING");
            importRepo.save(pi);

            // 1. 骨架扫描
            Skeleton skeleton = scanSkeleton(root);
            pi.setTechStack(toJson(skeleton.techStack));
            importRepo.save(pi);
            log.info("骨架扫描完成 (project={}): 技术栈={}, 文件数={}", pi.getName(),
                    skeleton.techStack, skeleton.fileCount);

            // 2. LLM 识别业务域
            List<DomainAssignment> assignments = identifyDomains(skeleton, pi.getName());
            log.info("LLM 识别出 {} 个业务域 (project={})", assignments.size(), pi.getName());

            // 3. 并行派发「域 agent」分析代码（每域一个任务，单域失败不拖垮整项目）
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < assignments.size(); i++) {
                DomainAssignment da = assignments.get(i);
                final int sortOrder = i;
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        analyzeDomain(projectId, root, da, sortOrder, userId);
                    } catch (Exception e) {
                        log.error("域「{}」分析失败（跳过该域，不影响其他域）: {}", da.name, e.getMessage());
                    }
                }, domainExecutor));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            // 4. 标记完成
            pi.setStatus("READY");
            importRepo.save(pi);
            log.info("项目分析完成 (project={}): {} 个域", pi.getName(), assignments.size());

        } catch (Exception e) {
            log.error("项目分析失败 (projectId={})", projectId, e);
            pi.setStatus("FAILED");
            pi.setErrorMsg(e.getMessage() != null ? e.getMessage() : "分析异常");
            importRepo.save(pi);
        }
    }

    // ============================================================
    //  骨架扫描
    // ============================================================

    /** 骨架扫描结果。 */
    static class Skeleton {
        List<String> techStack = new ArrayList<>();
        List<FileItem> files = new ArrayList<>();
        String treeSummary; // 目录树字符串（仅路径，用于 LLM 识别域）
        int fileCount;
    }

    static class FileItem {
        String relativePath;
        String fileName;
        long size;
        boolean isSource; // 是否源码文件（非二进制/非构建产物）
    }

    private Skeleton scanSkeleton(Path root) throws IOException {
        Skeleton sk = new Skeleton();
        List<String> treeLines = new ArrayList<>();
        List<FileItem> items = new ArrayList<>();
        int[] count = {0};

        // 先扫描 build 文件确定技术栈
        detectTechStack(root, sk.techStack);

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            int depth = 0;

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName().toString();
                if (SKIP_DIRS.contains(name) || name.startsWith(".")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                depth++;
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                depth--;
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String rel = root.relativize(file).toString();
                String name = file.getFileName().toString();
                String ext = ext(name).toLowerCase();

                // 目录树摘要（只记路径，不记二进制）
                if (count[0] < 2000) { // 限制 2000 条，防止撑爆 LLM
                    treeLines.add(rel);
                }
                count[0]++;

                // 跳过二进制/超大文件
                if (BINARY_EXTS.contains(ext) || attrs.size() > 5 * 1024 * 1024) {
                    return FileVisitResult.CONTINUE;
                }
                // 跳过构建产物目录（已在 preVisit 处理，但这里再保护）
                if (isSkipped(rel)) return FileVisitResult.CONTINUE;

                FileItem fi = new FileItem();
                fi.relativePath = rel;
                fi.fileName = name;
                fi.size = attrs.size();
                fi.isSource = true;
                items.add(fi);
                return FileVisitResult.CONTINUE;
            }
        });

        sk.treeSummary = String.join("\n", treeLines);
        sk.files = items;
        sk.fileCount = count[0];
        return sk;
    }

    /** 从 build 文件检测技术栈。 */
    private void detectTechStack(Path root, List<String> stack) {
        try (Stream<Path> files = Files.walk(root, 3)) {
            files.filter(Files::isRegularFile).forEach(f -> {
                String name = f.getFileName().toString().toLowerCase();
                if (name.equals("pom.xml") || name.endsWith(".gradle") || name.endsWith(".gradle.kts")) {
                    stack.add("Java / Spring Boot");
                } else if (name.equals("package.json") || name.equals("yarn.lock") || name.equals("pnpm-lock.yaml")) {
                    stack.add("JavaScript / TypeScript");
                } else if (name.equals("cargo.toml")) {
                    stack.add("Rust");
                } else if (name.equals("go.mod") || name.equals("go.sum")) {
                    stack.add("Go");
                } else if (name.equals("requirements.txt") || name.equals("pyproject.toml") || name.equals("setup.py")) {
                    stack.add("Python");
                } else if (name.endsWith(".csproj")) {
                    stack.add("C# / .NET");
                } else if (name.equals("composer.json")) {
                    stack.add("PHP");
                }
                // 也检测前端框架
                if (name.equals("package.json")) {
                    try {
                        String content = Files.readString(f, StandardCharsets.UTF_8);
                        if (content.contains("\"react\"")) stack.add("React");
                        if (content.contains("\"vue\"")) stack.add("Vue.js");
                        if (content.contains("\"@angular\"")) stack.add("Angular");
                        if (content.contains("\"next\"")) stack.add("Next.js");
                        if (content.contains("\"vite\"")) stack.add("Vite");
                        if (content.contains("\"electron\"")) stack.add("Electron");
                    } catch (Exception ignored) {}
                }
            });
        } catch (Exception ignored) {}
        // 去重
        List<String> deduped = stack.stream().distinct().toList();
        stack.clear();
        stack.addAll(deduped);
    }

    // ============================================================
    //  LLM 识别业务域
    // ============================================================

    /** LLM 输出的域分配。 */
    static class DomainAssignment {
        public String name;
        public String description;
        public List<String> filePatterns; // 文件路径模式（如 "**/controller/*.java"）
    }

    private List<DomainAssignment> identifyDomains(Skeleton skeleton, String projectName) {
        String userPrompt = String.format("""
                项目名称：%s
                技术栈：%s
                文件总数：%d
                目录树（部分）：
                %s

                请分析这个项目的业务域/模块划分。从【业务功能】角度出发，而不是按文件或技术分层。
                每个业务域是一个完整的功能模块（如「用户管理」「订单处理」「对话系统」）。

                要求：
                - 域数量由项目实际内容决定，不预设上限下限
                - 每个域给出名称、一句话描述、以及该域涉及的文件路径模式（glob 或关键词）
                - 域之间不要重叠，一个文件最多属于一个域
                - 域名称用中文，简短精炼
                """, projectName, skeleton.techStack, skeleton.fileCount,
                truncate(skeleton.treeSummary, 10000));

        DomainAssignment[] result = invoker.invoke(DOMAIN_SYSTEM, userPrompt, DomainAssignment[].class);
        if (result == null) return List.of();
        return Arrays.stream(result).filter(d -> d != null && d.name != null && !d.name.isBlank()).toList();
    }

    private static final String DOMAIN_SYSTEM = """
            你是一位软件架构师，正在分析一个项目的源码结构。
            你的任务：根据项目的目录树和技术栈，从【业务功能】角度识别出该项目的所有业务域/模块。

            输出格式：JSON 数组，每个元素：
            {
              "name": "业务域名（中文，如「用户认证」「订单管理」）",
              "description": "一句话描述该域的功能",
              "filePatterns": ["涉及的文件路径模式，如 "**/auth/**" 或 "**/controller/UserController.java""]
            }

            注意：
            - 只识别业务域，不要按技术层（如"前端"、"后端"、"数据库"）拆
            - 尽量从目录名、包名、文件名推断业务职能
            - 域数量由项目实际决定，一个微服务项目可能只有 1-2 个域，一个大项目可能有 10+ 个
            - 只输出 JSON，不要其他内容
            """;

    // ============================================================
    //  逐域分析
    // ============================================================

    private void analyzeDomain(Long projectId, Path root, DomainAssignment da, int sortOrder, Long userId) {
        // 先创建域记录
        ProjectDomain domain = new ProjectDomain();
        domain.setProjectId(projectId);
        domain.setName(da.name);
        domain.setOverview(da.description);
        domain.setSortOrder(sortOrder);
        domain.setRefFiles(toJson(da.filePatterns));
        domain = domainRepo.save(domain);

        // 收集匹配该域的文件内容
        List<String> matchedFiles = matchFiles(root, da.filePatterns);
        StringBuilder codeBlock = new StringBuilder();
        List<String> readPaths = new ArrayList<>();
        for (String rel : matchedFiles) {
            Path full = root.resolve(rel);
            if (!Files.isRegularFile(full)) continue;
            if (codeBlock.length() > MAX_DOMAIN_CHARS) break; // 截断
            try {
                String content = Files.readString(full, StandardCharsets.UTF_8);
                if (content.isBlank()) continue;
                String snippet = "\n=== " + rel + " ===\n" + truncate(content, 3000);
                codeBlock.append(snippet);
                readPaths.add(rel);
            } catch (Exception ignored) {}
        }

        if (codeBlock.isEmpty()) {
            log.warn("域「{}」没有匹配到可读文件，跳过分析", da.name);
            return;
        }

        // 调用 LLM 分析该域
        String userPrompt = String.format("""
                业务域：%s
                描述：%s

                该业务域涉及以下文件，请分析其代码，从【这个项目具体如何实现】的角度，拆解出该域下的子知识点。

                每个子知识点对应一个关键机制 / 子模块 / 核心流程，不是单个文件。
                子知识点描述必须包含：
                - 这个项目在该子点上是怎么实现的（关键类、方法、设计模式）
                - 为什么这么设计（设计决策、权衡）
                - 涉及哪些文件（精确路径）

                代码内容：
                %s
                """, da.name, da.description, codeBlock);

        SubPointOutput[] subPoints = invoker.invoke(DOMAIN_ANALYSIS_SYSTEM, userPrompt, SubPointOutput[].class);
        if (subPoints == null || subPoints.length == 0) {
            log.warn("域「{}」分析未返回子知识点", da.name);
            return;
        }

        int subOrder = 0;
        for (SubPointOutput sp : subPoints) {
            if (sp == null || sp.name == null || sp.name.isBlank()) continue;
            ProjectSubPoint psp = new ProjectSubPoint();
            psp.setDomainId(domain.getId());
            psp.setName(sp.name);
            psp.setDescription(sp.description);
            psp.setRefFiles(toJson(sp.files));
            psp.setSortOrder(subOrder++);
            subPointRepo.save(psp);
        }
        log.info("域「{}」分析完成：{} 个子知识点", da.name, subOrder);
    }

    /** LLM 子点分析输出。 */
    static class SubPointOutput {
        public String name;
        public String description;
        public List<String> files;
    }

    private static final String DOMAIN_ANALYSIS_SYSTEM = """
            你是一位资深软件架构师，正在分析一个业务域的源码实现。

            你的任务：从【这个项目具体怎么实现】的角度，拆解出该域下的子知识点。

            每个子知识点对应一个关键机制 / 子模块 / 核心流程。
            子知识点描述必须包含这个项目在该点上是怎么实现的、为什么这么设计。

            输出格式：JSON 数组，每个元素：
            {
              "name": "子知识点名称（中文，如「SSE 流式协议与断连处理」）",
              "description": "项目专属描述。必须包含具体实现机制、设计决策、注意事项。不要写通用概念解释。",
              "files": ["精确的文件路径，与代码中给出的路径一致"]
            }

            要求：
            - 子知识点数量由该域的实际内容决定，不预设上限下限
            - 每个子知识点的描述不限长度，该写多少写多少
            - 必须在描述中体现「本项目是怎么做的」，而不是「这个概念是什么」
            - 只输出 JSON，不要其他内容
            """;

    // ============================================================
    //  工具方法
    // ============================================================

    /** 根据文件路径模式匹配项目中的文件。 */
    private List<String> matchFiles(Path root, List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) return List.of();
        // 把 glob 模式转为简单的路径包含匹配
        List<String> matches = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile).forEach(f -> {
                String rel = root.relativize(f).toString().replace('\\', '/');
                for (String p : patterns) {
                    if (p == null || p.isBlank()) continue;
                    String pattern = p.replace('\\', '/');
                    if (matchesSimple(rel, pattern)) {
                        // 只匹配源码文件
                        String ext = ext(rel).toLowerCase();
                        if (!BINARY_EXTS.contains(ext)) {
                            matches.add(rel);
                        }
                        break;
                    }
                }
            });
        } catch (Exception ignored) {}
        return matches;
    }

    /** Simple path matching: uses Java glob matching via FileSystem.getPathMatcher. */
    private boolean matchesSimple(String path, String pattern) {
        // 如果 pattern 不含通配符，直接做包含匹配
        if (!pattern.contains("*") && !pattern.contains("?")) {
            return path.contains(pattern);
        }
        // 统一加 **/ 前缀：让 "controller/*.java" 也能命中任意深度的 controller 目录
        String glob = pattern.startsWith("**/") ? pattern : "**/" + pattern;
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
        return matcher.matches(Path.of(path));
    }

    private static String ext(String name) {
        int idx = name.lastIndexOf('.');
        return idx < 0 ? "" : name.substring(idx);
    }

    private static boolean isSkipped(String rel) {
        String lower = rel.toLowerCase();
        return lower.contains("node_modules") || lower.contains(".git/")
                || lower.contains("target/") || lower.contains("build/")
                || lower.contains("dist/") || lower.contains("__pycache__")
                || lower.contains(".gradle/") || lower.contains("venv/");
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max) + "\n…（已截断）";
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 从文件名提取项目名（去掉 .zip / .tar.gz 等后缀）。 */
    private String extractProjectName(String fileName) {
        if (fileName == null) return "项目";
        String name = fileName;
        // 去掉常见压缩后缀
        for (String suffix : new String[]{".tar.gz", ".tar.bz2", ".zip", ".tar", ".gz", ".bz2", ".7z"}) {
            if (name.toLowerCase().endsWith(suffix)) {
                name = name.substring(0, name.length() - suffix.length());
                break;
            }
        }
        return name.isBlank() ? "项目" : name;
    }

    /** 解压 zip 到目标目录。 */
    private void unzip(InputStream in, Path targetDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path out = targetDir.resolve(entry.getName()).normalize();
                if (!out.startsWith(targetDir)) {
                    throw new IOException("zip 路径穿越: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }

    // ============================================================
    //  视图 DTO
    // ============================================================

    public record ProjectStatus(Long id, String name, String techStack,
                                String status, String errorMsg, List<DomainView> domains) {}

    public record DomainView(Long id, String name, String overview,
                             List<String> refFiles, List<SubPointView> subPoints) {}

    public record SubPointView(Long id, String name, String description,
                               List<String> refFiles) {}
}