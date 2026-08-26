package interview.homegrown.modules.project.web;

import interview.homegrown.modules.project.domain.ProjectImport;
import interview.homegrown.modules.project.service.ProjectAnalysisService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/**
 * 项目导入与分析控制器。
 * <p>
 * 端点：
 * <ul>
 *   <li>POST /api/project/import-zip — 上传 zip 项目文件</li>
 *   <li>POST /api/project/import-path — 桌面端指定本地路径</li>
 *   <li>GET  /api/project/{id} — 查询项目分析状态与结果</li>
 *   <li>POST /api/project/{id}/create-plan — 创建学习计划</li>
 *   <li>GET  /api/project — 列出我的所有导入项目</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/project")
public class ProjectController {

    private final ProjectAnalysisService analysisService;

    public ProjectController(ProjectAnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    /** 上传 zip 项目文件。 */
    @PostMapping("/import-zip")
    public ResponseEntity<Map<String, Object>> importZip(@RequestParam("file") MultipartFile file) {
        Long uid = currentUserId();
        ProjectImport pi = analysisService.importZip(file, uid);
        return ResponseEntity.ok(Map.of(
                "id", pi.getId(),
                "name", pi.getName(),
                "status", pi.getStatus()
        ));
    }

    /** 桌面端：直接读本地路径（免上传）。 */
    @PostMapping("/import-path")
    public ResponseEntity<Map<String, Object>> importPath(@RequestBody Map<String, String> body) {
        Long uid = currentUserId();
        String path = body.get("path");
        if (path == null || path.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "path 不能为空");
        }
        ProjectImport pi = analysisService.importPath(path, uid);
        return ResponseEntity.ok(Map.of(
                "id", pi.getId(),
                "name", pi.getName(),
                "status", pi.getStatus()
        ));
    }

    /** 查询项目分析状态与结果。 */
    @GetMapping("/{id}")
    public ProjectAnalysisService.ProjectStatus getStatus(@PathVariable Long id) {
        return analysisService.getStatus(currentUserId(), id);
    }

    /** 列出我的所有导入项目。 */
    @GetMapping
    public List<ProjectAnalysisService.ProjectStatus> list() {
        return analysisService.listForUser(currentUserId());
    }

    /** 创建学习计划（基于分析结果）。 */
    @PostMapping("/{id}/create-plan")
    public ResponseEntity<Map<String, Object>> createPlan(@PathVariable Long id) {
        Long uid = currentUserId();
        var planView = analysisService.createPlan(uid, id);
        return ResponseEntity.ok(Map.of(
                "ok", true,
                "planId", planView.id()
        ));
    }

    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Long)) {
            throw new ResponseStatusException(UNAUTHORIZED, "未鉴权");
        }
        return (Long) auth.getPrincipal();
    }
}