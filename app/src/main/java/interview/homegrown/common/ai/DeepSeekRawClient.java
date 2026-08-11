package interview.homegrown.common.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.homegrown.common.config.AiConfigProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

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
 * <p>健壮性：若默认 Provider 配置不完整则<b>降级</b>（client 置空、complete 返回 null），
 * 绝不抛异常阻断应用启动。</p>
 */
@Component
public class DeepSeekRawClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekRawClient.class);

    private final HttpClient httpClient;
    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DeepSeekRawClient(AiConfigProperties config) {
        var provider = config.getProviders().get(config.getDefaultProvider());
        if (provider == null || !provider.isAvailable()) {
            this.httpClient = null;
            this.endpoint = null;
            this.apiKey = null;
            this.model = null;
            log.warn("DeepSeekRawClient 跳过初始化：默认 Provider 未配置完整，reasoning_content 兜底暂不可用");
            return;
        }
        this.model = provider.getModel();
        String base = provider.getBaseUrl();
        this.endpoint = base.endsWith("/chat/completions") ? base
                : (base.endsWith("/") ? base + "chat/completions" : base + "/chat/completions");
        this.apiKey = provider.getApiKey();

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        log.info("DeepSeekRawClient 初始化成功: model={}, endpoint={}", model, endpoint);
    }

    /**
     * 同步请求，content 为空时回退 reasoning_content。
     *
     * @return 答案文本；若两者皆空返回 null（由上层决定是否重试/抛错）
     */
    public String complete(String system, String user) {
        if (httpClient == null) return null;
        try {
            Map<String, Object> body = Map.of(
                    "model", model,
                    "messages", List.of(
                            Map.of("role", "system", "content", system),
                            Map.of("role", "user", "content", user)),
                    "temperature", 0.7);
            String resp = post(body, /*stream*/ false);

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

    /**
     * 流式请求：逐 token 通过 sink 回调。请求体加 {@code stream: true}，按 SSE 数据行解析
     * {@code choices[0].delta.content}；非数据行（done/ping/heartbeat）忽略。
     *
     * <p>失败时 sink 不会被回调，上层可根据 stream 收尾为空字符串判定为失败。
     *
     * @param onToken             收到一个 delta content token 时回调（不含换行）
     * @param onError             流式过程中出现异常时回调（仅一次）；为 null 则只 log
     * @param fallbackToReasoning 当 {@code content} 为空时是否读取 {@code reasoning_content}。
     *                            结构化输出/出题场景建议 true（deepseek 偶发把答案塞 reasoning_content）；
     *                            面向用户的讲解/对话场景必须 false，否则会把模型内部思考（含 prompt 复述）暴露给用户。
     */
    public void stream(String system, String user, Consumer<String> onToken, Consumer<Throwable> onError,
                       boolean fallbackToReasoning) {
        if (httpClient == null) {
            notifyError(onError, new IllegalStateException("DeepSeekRawClient 未初始化"));
            return;
        }
        try {
            Map<String, Object> body = Map.of(
                    "model", model,
                    "messages", List.of(
                            Map.of("role", "system", "content", system),
                            Map.of("role", "user", "content", user)),
                    "temperature", 0.7,
                    "max_tokens", 4096,
                    "stream", true);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .timeout(Duration.ofSeconds(120))
                    .build();

            // 关键：用 ofInputStream() 而不是 ofLines() —— JDK 的 ofLines() 是"全缓冲"的
            // （等整个响应体到达后才产生行流），根本做不到真流式；ofInputStream + BufferedReader
            // readLine() 才是逐行阻塞读，deepseek 每推一个 chunk 就能立刻回调 onToken。
            HttpResponse<InputStream> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                notifyError(onError, new RuntimeException("LLM stream HTTP " + response.statusCode()));
                return;
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // SSE 一条 event 可能跨多行："data: ..." 一行；空行表示事件边界
                    if (line.isEmpty()) continue;
                    if (!line.startsWith("data:")) continue;
                    String payload = line.substring(5).trim();
                    if (payload.isEmpty() || "[DONE]".equals(payload)) continue;
                    try {
                        JsonNode node = objectMapper.readTree(payload);
                        JsonNode delta = node.path("choices").path(0).path("delta");
                        // 教学讲解等面向用户的场景严禁回退 reasoning_content，否则会把模型内部思考
                        // （常包含 prompt 复述）直接暴露给用户。
                        // 注意：不能用 textOrNull 过滤"纯空白"的 delta —— 流式模型常把单独的换行
                        // （"\n"）作为独立 token 下发，isBlank() 会把它当空丢掉，导致代码/段落换行丢失、
                        // markdown 代码块缺行。这里只判缺失/空，纯空白 token 原样保留。
                        String text = streamText(delta.path("content"));
                        if (text == null && fallbackToReasoning) {
                            text = streamText(delta.path("reasoning_content"));
                        }
                        if (text != null && !text.isEmpty()) onToken.accept(text);
                    } catch (Exception e) {
                        log.debug("解析 SSE chunk 失败，跳过: {}", e.getMessage());
                    }
                }
            }
        } catch (Throwable t) {
            notifyError(onError, t);
        }
    }

    /**
     * 流式请求：默认允许回退 reasoning_content（与旧行为兼容）。
     *
     * @see #stream(String, String, Consumer, Consumer, boolean)
     */
    public void stream(String system, String user, Consumer<String> onToken, Consumer<Throwable> onError) {
        stream(system, user, onToken, onError, true);
    }

    private void notifyError(Consumer<Throwable> onError, Throwable t) {
        log.warn("DeepSeek stream 失败: {}", t.getMessage());
        if (onError != null) {
            try {
                onError.accept(t);
            } catch (Exception ignored) {
            }
        }
    }

    /** 同步 POST + 完整 body，stream=false。供 complete() 使用 */
    private String post(Map<String, Object> body, boolean stream) throws Exception {
        Map<String, Object> withStream = stream ? body : body;  // stream 字段由调用方决定是否加
        String json = objectMapper.writeValueAsString(withStream);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .timeout(Duration.ofSeconds(60))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new RuntimeException("LLM HTTP " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }

    private String textOrNull(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return null;
        String s = node.asText();
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * 流式 token 提取：缺失/null 返回 null；其余原样返回（<b>含纯空白</b>）。
     * 单独的换行 token（"\n"）不能当空丢掉，否则代码/段落的换行会在流里消失。
     */
    private static String streamText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        return node.asText();
    }
}
