package interview.homegrown.common.config;


import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * AI 多 Provider 配置
 * 绑定 application.yml 中 app.ai.* 的配置项
 * 支持定义多个 LLM Provider（DashScope、DeepSeek、Kimi 等），
 * 每个 Provider 使用 OpenAI 兼容协议。
 */
@Configuration
@ConfigurationProperties(prefix = "app.ai")
public class AiConfigProperties {

    private String defaultProvider = "dashscope";

    private Structured structured = new Structured();

    private Map<String, ProviderConfig> providers = new HashMap<>();

    // ---- getters & setters ----
    public String getDefaultProvider() {
        return defaultProvider;
    }
    public void setDefaultProvider(String defaultProvider) {
        this.defaultProvider = defaultProvider;
    }
    public Structured getStructured() {
        return structured;
    }
    public void setStructured(Structured structured) {
        this.structured = structured;
    }
    public Map<String, ProviderConfig> getProviders() {
        return providers;
    }
    public void setProviders(Map<String, ProviderConfig> providers) {
        this.providers = providers;
    }


    public static class Structured{

        private int maxAttempts;

        private boolean includeLastError;

        // ---- getters & setters ----
        public int getMaxAttempts() {
            return maxAttempts;
        }
        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }
        public boolean isIncludeLastError() {
            return includeLastError;
        }
        public void setIncludeLastError(boolean includeLastError) {
            this.includeLastError = includeLastError;
        }
    }

    public static class ProviderConfig{

        private String baseUrl;

        private String apiKey;

        private String model;

        private double temperature = 0.7;

        //检测 Provider 是否已经配置完整（可连接）
        public boolean isAvailable(){
            return baseUrl != null && !baseUrl.isEmpty()
                    &&apiKey != null && !apiKey.isBlank()
                    &&model != null && !model.isBlank();
        }

        // ---- getters & setters ----
        public String getBaseUrl() {
            return baseUrl;
        }
        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
        public String getApiKey() {
            return apiKey;
        }
        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }
        public String getModel() {
            return model;
        }
        public void setModel(String model) {
            this.model = model;
        }
        public double getTemperature() {
            return temperature;
        }
        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }
    }


}
