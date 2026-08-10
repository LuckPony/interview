package interview.homegrown.common.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.homegrown.common.config.AiConfigProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek 原生直连客户端（绕过 Spring AI 的 OpenAI 适配器）。
 *
 * <p>背景：Spring AI 2.0 的 OpenAiChatModel 只读 {@code content}、把 {@code reasoning_content} 丢弃。
 * 而 deepseek-v4-flash / v4-pro 是推理模型，偶发把最终答案塞进 {@code reasoning_content}、
 * 让 {@code content} 为空 —— 这正是「出题 500 / LLM 返回为空」的根因。</p>
 *
 * <p>本客户端直连 {@code /chat/completions}，{@code content} 为空时回退读 {@code reasoning_content}，
 * 供 {@link StructuredOutputInvoker} 在 Spring AI 返回空时兜底，使结构化输出路径不再因推理模型而 500。</p>
 *
 * <p>健壮性：若默认 Provider 配置不完整则<b>降级</b>（restClient 置空、complete 返回 null），
 * 绝不抛异常阻断应用启动。</p>
 */
@Component
public class DeepSeekRawClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekRawClient.class);

    private final RestClient restClient;
    private final String model;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DeepSeekRawClient(AiConfigProperties config) {
        var provider = config.getProviders().get(config.getDefaultProvider());
        if (provider == null || !provider.isAvailable()) {
            this.restClient = null;
            this.model = null;
            log.warn("DeepSeekRawClient 跳过初始化：默认 Provider 未配置完整，reasoning_content 兜底暂不可用");
            return;
        }
        this.model = provider.getModel();

        String base = provider.getBaseUrl();
        String endpoint = base.endsWith("/chat/completions") ? base
                : (base.endsWith("/") ? base + "chat/completions" : base + "/chat/completions");

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(60));

        this.restClient = RestClient.builder()
                .baseUrl(endpoint)
                .requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + provider.getApiKey())
                .defaultHeader("Content-Type", "application/json")
                .build();
        log.info("DeepSeekRawClient 初始化成功: model={}, endpoint={}", model, endpoint);
    }

    /**
     * 直连请求，content 为空时回退 reasoning_content。
     *
     * @return 答案文本；若两者皆空返回 null（由上层决定是否重试/抛错）
     */
    public String complete(String system, String user) {
        if (restClient == null) return null;
        try {
            Map<String, Object> body = Map.of(
                    "model", model,
                    "messages", List.of(
                            Map.of("role", "system", "content", system),
                            Map.of("role", "user", "content", user)),
                    "temperature", 0.7);
            String resp = restClient.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(resp);
            JsonNode msg = root.path("choices").path(0).path("message");

            String content = textOrNull(msg.path("content"));
            if (content != null) return content;

            String reasoning = textOrNull(msg.path("reasoning_content"));
            if (reasoning != null) {
                log.debug("content 为空，回退 reasoning_content（长度={}）", reasoning.length());
                return reasoning;
            }
            return null;
        } catch (Exception e) {
            log.warn("DeepSeek 原生请求失败: {}", e.getMessage());
            return null;
        }
    }

    private String textOrNull(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return null;
        String s = node.asText();
        return (s == null || s.isBlank()) ? null : s;
    }
}
