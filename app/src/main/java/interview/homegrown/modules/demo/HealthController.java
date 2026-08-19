package interview.homegrown.modules.demo;

import interview.homegrown.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.DatabaseMetaData;
import java.util.HashMap;
import java.util.Map;

/*
  演示用 Controller —— 验证数据库连通性
  （Redis / MinIO 已移除，单机自包含只依赖嵌入式 H2）
 */
@RestController
@RequestMapping("/api/demo")
@Tag(name = "基础设施健康检查", description = "验证数据库连通性")
public class HealthController {

    private final DataSource dataSource;

    public HealthController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping("/health")
    @Operation(summary = "健康检查", description = "检测数据库连通状态")
    public Result<Map<String, Object>> health() {
        Map<String, Object> status = new HashMap<>();
        status.put("app", "UP");
        status.put("timestamp", System.currentTimeMillis());

        try (var conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            Map<String, Object> dbInfo = new HashMap<>();
            dbInfo.put("status", "UP");
            dbInfo.put("product", meta.getDatabaseProductName());
            dbInfo.put("version", meta.getDatabaseProductVersion());
            dbInfo.put("url", meta.getURL());
            status.put("database", dbInfo);
        } catch (Exception e) {
            status.put("database", Map.of("status", "DOWN", "error", e.getMessage()));
        }

        status.put("status", "UP");
        return Result.success(status);
    }
}
