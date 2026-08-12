package interview.homegrown.common.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.homegrown.common.config.AiConfigProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 用户可配置的 AI 运行设置：provider / base-url / api-key / model / temperature。
 * 持久化到 ai_setting 单行表；读取时优先 DB，缺省回落到 application.yml 的默认 provider。
 * LLM 客户端（LlmRawClient）每次调用都从这里取当前配置，改完设置立即生效、无需重启。
 */
@Service
public class AiSettingsService {

    private static final Logger log = LoggerFactory.getLogger(AiSettingsService.class);

    private final JdbcTemplate jdbc;
    private final AiConfigProperties startup;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile AiConfig current;

    public AiSettingsService(JdbcTemplate jdbc, AiConfigProperties startup) {
        this.jdbc = jdbc;
        this.startup = startup;
        this.current = loadFromDbOrDefault();
    }

    public AiConfig currentProvider() {
        return current;
    }

    public synchronized void update(AiConfig cfg) {
        // apiKey 留空 = 沿用当前已存的 key，避免前端只改模型时把密钥覆盖成空
        AiConfig merged = cfg;
        if (cfg.apiKey() == null || cfg.apiKey().isBlank()) {
            merged = new AiConfig(cfg.provider(), cfg.baseUrl(), current.apiKey(),
                    cfg.model(), cfg.temperature());
        }
        try {
            jdbc.update("""
                    INSERT INTO ai_setting (id, settings_json) VALUES (1, ?)
                    ON CONFLICT (id) DO UPDATE SET settings_json = EXCLUDED.settings_json, updated_at = now()
                    """, objectMapper.writeValueAsString(merged));
        } catch (Exception e) {
            log.warn("保存 AI 设置失败: {}", e.getMessage());
        }
        this.current = merged;
    }

    private AiConfig loadFromDbOrDefault() {
        try {
            List<String> rows = jdbc.query(
                    "SELECT settings_json FROM ai_setting WHERE id = 1",
                    (rs, i) -> rs.getString(1));
            if (!rows.isEmpty() && rows.get(0) != null && !rows.get(0).isBlank()) {
                return objectMapper.readValue(rows.get(0), AiConfig.class);
            }
        } catch (Exception e) {
            log.warn("读取 AI 设置失败，用启动配置兜底: {}", e.getMessage());
        }
        var cfg = startup.getProviders().get(startup.getDefaultProvider());
        return cfg == null
                ? new AiConfig(startup.getDefaultProvider(), "", "", "", 0.7)
                : new AiConfig(startup.getDefaultProvider(), cfg.getBaseUrl(), cfg.getApiKey(),
                        cfg.getModel(), cfg.getTemperature());
    }
}
