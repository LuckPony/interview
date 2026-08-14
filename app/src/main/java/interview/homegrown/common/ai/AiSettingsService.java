package interview.homegrown.common.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.homegrown.common.config.AiConfigProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

/**
 * AI 运行设置：provider / base-url / api-key / model / temperature，<b>按用户隔离</b>。
 *
 * <p>LLM 每次调用实时解析，两层取值优先级（改完立即生效，无需重启）：</p>
 * <ol>
 *   <li><b>请求头 X-LLM-Key</b> —— 桌面端「仅本机保存」的 key，随请求带给后端，<b>只用不存</b>；</li>
 *   <li><b>当前登录用户的个人配置</b> —— Web 端保存到 user_ai_setting 表（每人一行，互不可见）。</li>
 * </ol>
 *
 * <p>不再支持「服务器级 / 共享 key」（application.yml 的 providers 不配 key、
 * 老 ai_setting 全局表已清空且不参与取值）：用户没存 key 时，LLM 调用会明确提示先去设置页填写，
 * 而不是静默使用任何默认 key。</p>
 */
@Service
public class AiSettingsService {

    private static final Logger log = LoggerFactory.getLogger(AiSettingsService.class);

    private final JdbcTemplate jdbc;
    private final AiConfigProperties startup;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AiSettingsService(JdbcTemplate jdbc, AiConfigProperties startup) {
        this.jdbc = jdbc;
        this.startup = startup;
    }

    /** 当前请求生效的配置（LLM 客户端每次调用走这里）。 */
    public AiConfig currentProviderForRequest() {
        // 2) 当前登录用户的个人配置
        Long userId = currentUserId();
        AiConfig base = userId != null ? loadFromDb(userId) : null;
        if (base == null) {
            base = startupConfig(); // 3) 启动配置（本地模式 = 自己的 key；云端 = 空）
        }
        // 1) 桌面端请求头 key 覆盖（只用不存）
        String headerKey = currentRequestLlmKey();
        if (headerKey != null && !headerKey.isBlank()) {
            return new AiConfig(base.provider(), base.baseUrl(), headerKey, base.model(), base.temperature());
        }
        return base;
    }

    /** 启动配置兜底：只提供默认 base-url / model 供设置页预填，【key 恒为空】——
     *  不再支持服务器级/共享 key，无用户配置时必须提示用户自己去填 key。 */
    public AiConfig currentProvider() {
        return startupConfig();
    }

    /** 保存当前用户的个人 AI 配置（apiKey 留空 = 沿用当前生效的 key，避免只改模型时覆盖成空）。 */
    public synchronized void update(Long userId, AiConfig cfg) {
        AiConfig existing = loadFromDb(userId);
        String finalKey = (cfg.apiKey() != null && !cfg.apiKey().isBlank())
                ? cfg.apiKey()
                : (existing != null ? existing.apiKey() : currentProvider().apiKey());
        AiConfig merged = new AiConfig(cfg.provider(), cfg.baseUrl(), finalKey, cfg.model(), cfg.temperature());
        try {
            jdbc.update("""
                    INSERT INTO user_ai_setting (user_id, settings_json) VALUES (?, ?)
                    ON CONFLICT (user_id) DO UPDATE SET settings_json = EXCLUDED.settings_json, updated_at = now()
                    """, userId, objectMapper.writeValueAsString(merged));
        } catch (Exception e) {
            log.warn("保存 AI 设置失败: {}", e.getMessage());
        }
    }

    /** 当前登录用户 id（JwtAuthFilter 写入 principal）；未登录返回 null。 */
    public Long currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Long userId) return userId;
        return null;
    }

    private AiConfig loadFromDb(Long userId) {
        try {
            List<String> rows = jdbc.query(
                    "SELECT settings_json FROM user_ai_setting WHERE user_id = ?",
                    (rs, i) -> rs.getString(1), userId);
            if (!rows.isEmpty() && rows.get(0) != null && !rows.get(0).isBlank()) {
                return objectMapper.readValue(rows.get(0), AiConfig.class);
            }
        } catch (Exception e) {
            log.warn("读取用户 AI 设置失败: {}", e.getMessage());
        }
        return null;
    }

    private String currentRequestLlmKey() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            return sra.getRequest().getHeader("X-LLM-Key");
        }
        return null;
    }

    private AiConfig startupConfig() {
        var cfg = startup.getProviders().get(startup.getDefaultProvider());
        return cfg == null
                ? new AiConfig(startup.getDefaultProvider(), "", "", "", 0.7)
                : new AiConfig(startup.getDefaultProvider(), cfg.getBaseUrl(), "",
                        cfg.getModel(), cfg.getTemperature());
    }
}
