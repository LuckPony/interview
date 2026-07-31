package interview.homegrown.modules.demo;

import interview.homegrown.common.result.Result;
import interview.homegrown.infrastructure.file.FileStorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.DatabaseMetaData;
import java.util.HashMap;
import java.util.Map;

/*
  演示用 Controller —— 验证所有基础设施服务是否连通
  阶段 2 结束后可以通过此接口快速验证
 */
@RestController
@RequestMapping("/api/demo")
@Tag(name = "基础设施健康检查",description = "验证 PostgreSQL / Redis / MinIO 连通性")
public class HealthController {

    private final DataSource dataSource;
    private final StringRedisTemplate redisTemplate;
    private final FileStorageService fileStorageService;

    public HealthController(DataSource dataSource, StringRedisTemplate redisTemplate, FileStorageService fileStorageService) {
        this.dataSource = dataSource;
        this.redisTemplate = redisTemplate;
        this.fileStorageService = fileStorageService;
    }

    //综合健康检查：验证postgreSQL + Redis + MinIO 全部连通
    @GetMapping("/health")
    @Operation(summary = "综合健康检查", description = "同时检测数据库、缓存、对象存储三个服务的连通状态")
    public Result<Map<String, Object>> health(){
        Map<String, Object> status = new HashMap<>();
        status.put("app","UP");
        status.put("timestamp", System.currentTimeMillis());

        //验证 PostgreSQL
        try(var conn = dataSource.getConnection()){
            DatabaseMetaData meta = conn.getMetaData();
            Map<String, Object> dbInfo = new HashMap<>();
            dbInfo.put("status","UP");
            dbInfo.put("product",meta.getDatabaseProductName());
            dbInfo.put("version",meta.getDatabaseProductVersion());
            dbInfo.put("url",meta.getURL());
            status.put("database",dbInfo);
        }catch (Exception e){
            status.put("database",Map.of("status","DOWN","error",e.getMessage()));
        }

        //验证redis
        try{
            redisTemplate.opsForValue().set("health:ping","pong");
            String pong = redisTemplate.opsForValue().get("health:ping");
            Map<String, Object> redisInfo = new HashMap<>();
            redisInfo.put("status","UP");
            redisInfo.put("ping",pong);
            status.put("redis",redisInfo);
        }catch (Exception e){
            status.put("redis",Map.of("status","DOWN","error",e.getMessage()));
        }

        //验证MinIO
        try{
            var url = fileStorageService.getUrl("health-check.txt");
            Map<String, Object> storageInfo = new HashMap<>();
            storageInfo.put("status","UP");
            storageInfo.put("bucketUrl",url.toString());
            status.put("storage",storageInfo);
        }catch (Exception e){
            status.put("redis",Map.of("status","DOWN","error",e.getMessage()));
        }

        //汇总：如果有任何一项 DOWN，整体就是 DEGRADED
        boolean allup = status.values().stream()
                .filter(v -> v instanceof Map)
                .map(v -> ((Map<?,?>) v).get("status"))
                .noneMatch("DOWN"::equals);
        if(!allup){
            status.put("status","DOWN");
        }
        return Result.success(status);
    }
}
