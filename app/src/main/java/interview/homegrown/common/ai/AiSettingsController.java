package interview.homegrown.common.ai;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 设置页：读取 / 更新 AI 模型配置（provider / base-url / api-key / model / temperature）。 */
@RestController
@RequestMapping("/api/settings/ai")
public class AiSettingsController {

    private final AiSettingsService settings;

    public AiSettingsController(AiSettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    public Map<String, Object> get() {
        AiConfig c = settings.currentProvider();
        return Map.of(
                "provider", c.provider(),
                "baseUrl", c.baseUrl(),
                "model", c.model(),
                "apiKeyMasked", mask(c.apiKey()),
                "temperature", c.temperature());
    }

    @PostMapping
    public Map<String, Object> update(@RequestBody AiConfig cfg) {
        settings.update(cfg);
        return Map.of("ok", true, "provider", cfg.provider(), "model", cfg.model());
    }

    private String mask(String key) {
        if (key == null || key.isEmpty()) return "";
        if (key.length() <= 4) return "****";
        return "****" + key.substring(key.length() - 4);
    }
}
