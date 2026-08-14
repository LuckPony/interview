package interview.homegrown.common.ai;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 设置页：读取 / 更新「当前登录用户」的 AI 模型配置（provider / base-url / api-key / model / temperature）。 */
@RestController
@RequestMapping("/api/settings/ai")
public class AiSettingsController {

    private final AiSettingsService settings;

    public AiSettingsController(AiSettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    public Map<String, Object> get() {
        AiConfig c = settings.currentProviderForRequest();
        return Map.of(
                "provider", c.provider(),
                "baseUrl", c.baseUrl(),
                "model", c.model(),
                // 不回显 key 的任何片段（连掩码都不给），只告诉前端「有没有配置」
                "hasApiKey", c.apiKey() != null && !c.apiKey().isBlank(),
                "temperature", c.temperature());
    }

    @PostMapping
    public Map<String, Object> update(@RequestBody AiConfig cfg) {
        Long userId = settings.currentUserId();
        if (userId == null) {
            throw new IllegalStateException("未登录");
        }
        settings.update(userId, cfg);
        return Map.of("ok", true, "provider", cfg.provider(), "model", cfg.model());
    }
}
