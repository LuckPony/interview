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
import java.util.Set;
import java.util.function.Consumer;

/**
 * 默认 Provider 的 OpenAI 兼容原生直连客户端（绕过 Spring AI 的 OpenAI 适配器）。
 *
 * <p>背景：Spring AI 2.0 的 OpenAiChatModel 只读 {@code content}、把 {@code reasoning_content} 丢弃。
 * 而推理模型（如 deepseek-v4-flash）偶发把最终答案塞进 {@code reasoning_content}、
 * 让 {@code content} 为空 —— 这正是「出题 500 / LLM 返回为空」的根因。</p>
 *
 * <p>本客户端直连 {@code /chat/completions}，{@code content} 为空时回退读 {@code reasoning_content}，
 * 供结构化输出与流式讲解使用。它<b>跟随 {@code default-provider} 配置</b>（base-url / api-key / model），
 * 不是 DeepSeek 专用——切换模型只需改配置里的 default-provider 并填好对应 key。</p>
 *
 * <p>健壮性：若默认 Provider 配置不完整则<b>降级</b>（client 置空、complete 返回 null），
 * 绝不抛异常阻断应用启动。</p>
 */
@Component
public class LlmRawClient {

    private static final Logger log = LoggerFactory.getLogger(LlmRawClient.class);

    private final HttpClient httpClient;
    private final AiSettingsService settings;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LlmRawClient(AiSettingsService settings) {
        this.settings = settings;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        var cfg = settings.currentProvider();
        log.info("LlmRawClient 就绪: provider={}, model={}", cfg.provider(), cfg.model());
    }

    /** 当前生效配置：桌面端请求头 X-LLM-Key（只用不存）> 当前登录用户个人配置 > 启动配置（.env）。 */
    private AiConfig cfg() {
        return settings.currentProviderForRequest();
    }

    private boolean available() {
        AiConfig c = cfg();
        return c != null && c.apiKey() != null && !c.apiKey().isBlank()
                && c.model() != null && !c.model().isBlank();
    }

    /** 当前请求链路是否具备可用配置（请求头 X-LLM-Key / 登录用户设置 / 启动配置任一有 key）。 */
    public boolean availableForCurrentRequest() {
        return available();
    }

    private String endpoint() {
        String base = cfg().baseUrl();
        return base.endsWith("/chat/completions") ? base
                : (base.endsWith("/") ? base + "chat/completions" : base + "/chat/completions");
    }

    /** base URL 根（去掉尾部 /chat/completions），用于拼 /responses 等协议端点。 */
    private String baseRoot() {
        String base = cfg().baseUrl();
        if (base.endsWith("/chat/completions")) {
            base = base.substring(0, base.length() - "/chat/completions".length());
        }
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /**
     * 互联网搜索 / 读取指定 URL（联网能力）。
     *
     * <p>两级尝试：
     * 1) <b>Responses API</b>：POST {@code <root>/responses}，带内置 {@code web_search} 工具
     *    （DeepSeek 官方文档确认 deepseek-v4-flash/pro 支持，服务端执行搜索）；
     * 2) <b>chat/completions + tools</b>：部分 OpenAI 兼容中转也在 /chat/completions 上
     *    接受 {@code web_search} 工具，作为回退。
     *
     * <p>query 可以是关键词，也可以是具体 URL（"请读取这个链接的内容：…"），由供应商搜索工具处理。
     * 失败返回 null，由上层降级（不阻塞）。结果由调用方负责截断。
     */
    public String webSearch(String query) {
        if (!available()) return null;
        if (query == null || query.isBlank()) return null;

        // 1) Responses API（内置 web_search 工具）
        try {
            Map<String, Object> body = new java.util.HashMap<>();
            body.put("model", cfg().model());
            body.put("input", query);
            body.put("instructions", "请基于联网搜索到的内容回答。引用时尽量注明来源链接。");
            body.put("tools", List.of(Map.of("type", "web_search")));
            body.put("stream", false);
            String resp = postTo(baseRoot() + "/responses", body, 150);
            String text = extractResponsesText(resp);
            if (text != null && !text.isBlank()) return text;
        } catch (Exception e) {
            log.debug("Responses API web_search 失败（尝试 chat/completions 方式）: {}", e.getMessage());
        }

        // 2) chat/completions + tools 回退
        try {
            Map<String, Object> body = new java.util.HashMap<>();
            body.put("model", cfg().model());
            body.put("messages", List.of(Map.of("role", "user", "content", query)));
            body.put("tools", List.of(Map.of("type", "web_search")));
            body.put("stream", false);
            String resp = postWithRetry(body);
            JsonNode root = objectMapper.readTree(resp);
            String content = textOrNull(root.path("choices").path(0).path("message").path("content"));
            if (content != null && !content.isBlank()) return content;
        } catch (Exception e) {
            log.debug("chat/completions web_search 失败: {}", e.getMessage());
        }
        return null;
    }

    /** 从 Responses API 响应里抽取 message 文本（拼接 output 中的全部 text 内容块）。 */
    private String extractResponsesText(String resp) throws Exception {
        JsonNode root = objectMapper.readTree(resp);
        JsonNode output = root.path("output");
        if (!output.isArray()) return null;
        StringBuilder sb = new StringBuilder();
        for (JsonNode item : output) {
            if (!"message".equals(item.path("type").asText())) continue;
            JsonNode content = item.path("content");
            if (!content.isArray()) continue;
            for (JsonNode c : content) {
                String t = c.path("text").asText("");
                if (!t.isBlank()) sb.append(t).append("\n\n");
            }
        }
        return sb.toString().isBlank() ? null : sb.toString().trim();
    }

    /**
     * 同步请求，content 为空时回退 reasoning_content。
     *
     * @return 答案文本；若两者皆空返回 null（由上层决定是否重试/抛错）
     */
    public String complete(String system, String user) {
        if (!available()) return null;
        try {
            Map<String, Object> body = new java.util.HashMap<>();
            body.put("model", cfg().model());
            body.put("messages", List.of(
                    Map.of("role", "system", "content", system),
                    Map.of("role", "user", "content", user)));
            body.put("temperature", 0.7);
            // 上限要给足：学习规划一次性要吐 50-120 个知识点的 JSON，太小会被截断（表现为「只给 30 个」+ 解析失败）。
            // 8192 token 足够容纳 ~150 个精简知识点；thinking 已关闭，不会因思考占额度。
            applyDefaultTokens(body);
            // 关闭思考：一次性结构化输出（出题/判分/复盘）不需要模型"想"很久。
            // 按 provider 适配思考开关（deepseek/glm/doubao→thinking，qwen→enable_thinking），
            // 其余 provider（OpenAI/Gemini/Kimi…）不加该参数，避免未知参数二开 400。
            applyThinkingDiscard(body);
            String resp = postWithRetry(body);

            JsonNode root = objectMapper.readTree(resp);
            JsonNode msg = root.path("choices").path(0).path("message");

            String content = textOrNull(msg.path("content"));
            if (content != null) return content;

            String reasoning = firstReasoningToken(msg);
            if (reasoning != null) {
                log.debug("content 为空，回退 reasoning（长度={}）", reasoning.length());
                return reasoning;
            }
            return null;
        } catch (Exception e) {
            log.warn("LLM 原生请求失败: {}", e.getMessage());
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
     * @param fallbackToReasoning 当 {@code content} 为空时是否把 reasoning_content 当正文：
     *                            结构化输出/出题建议 true（deepseek 偶发把答案塞 reasoning_content）；
     *                            已用 onReasoning 独立展示思考的场景可 false。
     * @param onReasoning         收到 delta reasoning_content token 时回调（独立展示思考过程）；为 null 不推。
     */
    public void stream(String system, String user, Consumer<String> onToken, Consumer<Throwable> onError,
                       boolean fallbackToReasoning, Consumer<String> onReasoning) {
        stream(system, user, null, onToken, onError, fallbackToReasoning, onReasoning);
    }

    /**
     * 流式请求（可带图片）：视觉模型时把用户消息拼成 OpenAI 兼容的
     * {@code content: [{type:text},{type:image_url,image_url:{url:"data:image/..."}}]}；
     * images 为空时与旧行为一致（content 为纯字符串）。
     */
    public void stream(String system, String user, List<String> images,
                       Consumer<String> onToken, Consumer<Throwable> onError,
                       boolean fallbackToReasoning, Consumer<String> onReasoning) {
        if (!available()) {
            notifyError(onError, new IllegalStateException("尚未配置 API Key，请到「设置」页填写后再试"));
            return;
        }
        try {
            Map<String, Object> body = new java.util.HashMap<>();
            body.put("model", cfg().model());
            Object content;
            if (images == null || images.isEmpty()) {
                content = user;
            } else {
                List<Map<String, Object>> parts = new java.util.ArrayList<>();
                parts.add(Map.of("type", "text", "text", user));
                for (String img : images) {
                    parts.add(Map.of("type", "image_url", "image_url", Map.of("url", img)));
                }
                content = parts;
            }
            body.put("messages", List.of(
                    Map.of("role", "system", "content", system),
                    Map.of("role", "user", "content", content)));
            body.put("temperature", 0.7);
            // 思考模式：保持开启（模型更聪明），但 max_tokens 和超时要给足，
            // 否则 reasoning_content 会吃掉额度截断回答 / 思考+回答超时。
            applyDefaultTokens(body);
            // 长思考（reasoning）与正文共享输出预算：深挖性内容容易把 8192 吃光，
            // 导致正文被截断甚至为空（表现为「讲解生成失败」）。这里给足到 16384；
            // 若某 provider 不认该上限，由 400 去参重试兜底。非思考 provider 仅作上限，无副作用。
            applyGenerousBudget(body);
            // 按 provider 适配思考开关（deepseek/glm/doubao→thinking，qwen→enable_thinking），
            // 其余 provider 不加该参数 —— 让非 DeepSeek 的思考模型也能流式返回 reasoning_content/reasoning。
            applyThinkingStream(body);
            body.put("stream", true);

            // 关键：用 ofInputStream() 而不是 ofLines() —— JDK 的 ofLines() 是"全缓冲"的
            // （等整个响应体到达后才产生行流），根本做不到真流式；ofInputStream + BufferedReader
            // readLine() 才是逐行阻塞读，deepseek 每推一个 chunk 就能立刻回调 onToken。
            HttpResponse<InputStream> response = sendStreamWithRetry(body, onError);
            if (response == null) return;
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
                        JsonNode choice = node.path("choices").path(0);
                        JsonNode delta = choice.path("delta");
                        // 推理内容独立推送（onReasoning 非空时走它，正文走 onToken，互不混用）。
                        // 思考字段可能出现在 delta（流式）、choice 或 message（部分网关/模型在收尾帧才给），都兼容。
                        // 注意：不能用 textOrNull 过滤"纯空白"的 delta —— 流式模型常把单独的换行
                        // （"\n"）作为独立 token 下发，isBlank() 会把它当空丢掉，导致代码/段落换行丢失、
                        // markdown 代码块缺行。这里只判缺失/空，纯空白 token 原样保留。
                        String reasoning = firstReasoningToken(delta);
                        if (reasoning == null) reasoning = firstReasoningToken(choice);
                        if (reasoning == null) reasoning = firstReasoningToken(choice.path("message"));
                        if (reasoning != null && !reasoning.isEmpty() && onReasoning != null) {
                            onReasoning.accept(reasoning);
                        }
                        String text = streamText(delta.path("content"));
                        if (text == null && fallbackToReasoning) {
                            text = reasoning;
                        }
                        if (text != null && !text.isEmpty() && onToken != null) onToken.accept(text);
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
        stream(system, user, onToken, onError, true, null);
    }

    /** 不单独展示思考（onReasoning 为 null）时用此重载。 */
    public void stream(String system, String user, Consumer<String> onToken, Consumer<Throwable> onError,
                       boolean fallbackToReasoning) {
        stream(system, user, onToken, onError, fallbackToReasoning, null);
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
        return postTo(endpoint(), body, 60);
    }

    /** 同步 POST 到指定 URL（自定义超时），返回响应体字符串。非 2xx 抛异常。 */
    private String postTo(String url, Map<String, Object> body, int timeoutSeconds) throws Exception {
        String json = objectMapper.writeValueAsString(body);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + cfg().apiKey())
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new RuntimeException("LLM HTTP " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }

    // ============================================================
    // 多 Provider 兼容：思考开关、token 上限、400 去参重试兜底
    // ============================================================

    /** 不同 provider 易报 400「Unrecognized argument / invalid parameter」的可选参数。 */
    private static final Set<String> OPTIONAL_KEYS = Set.of(
            "thinking", "enable_thinking", "reasoning_effort", "max_tokens", "max_completion_tokens",
            "temperature", "top_p", "n", "frequency_penalty", "presence_penalty", "stream_options");

    /** 流式思考的推理力度：越低思考越短、生成越快。DeepSeek V4/GLM 默认 high、OpenAI 默认中高；
     *  讲解这类 200-2000 字的知识点不需要深度思考，用 low 即可；想更深入可改 "medium"/"high"。 */
    private static final String STREAM_REASONING_EFFORT = "low";

    private String modelLower() {
        return cfg().model() == null ? "" : cfg().model().toLowerCase();
    }

    private String urlLower() {
        return cfg().baseUrl() == null ? "" : cfg().baseUrl().toLowerCase();
    }

    /** 是否像 DeepSeek / Zhipu(GLM) / Doubao 这类用 {@code thinking: {type}} 控制思考的国产推理协议。 */
    private boolean isThinkingParamProvider() {
        String m = modelLower(), u = urlLower();
        return m.contains("deepseek") || u.contains("deepseek")
                || m.contains("glm") || u.contains("bigmodel")
                || m.contains("doubao") || u.contains("volces")
                || m.contains("seed-oss") || u.contains("ark");
    }

    /**
     * 是否像 DashScope / Qwen 这类用 {@code enable_thinking: bool} 控制思考的协议。
     * 仅对支持思考的型号（qwen3 混合思考 / qwen-plus / qwen-max / qwq）下发；
     * 其它 qwen（如 qwen2.5、qwen-vl）不支持该参数，下发会 400（由去参重试兜住）。
     */
    private boolean isQwenThinkingProvider() {
        String m = modelLower(), u = urlLower();
        if (!(u.contains("dashscope") || m.contains("qwen") || m.contains("qwq"))) return false;
        return m.contains("qwen3") || m.contains("qwen-plus") || m.contains("qwen-max") || m.contains("qwq");
    }

    /** 结构化输出 / 非流式：关闭思考。其余 provider 不加参数，走协议默认。 */
    private void applyThinkingDiscard(Map<String, Object> body) {
        if (isThinkingParamProvider()) {
            body.put("thinking", Map.of("type", "disabled"));
        } else if (isQwenThinkingProvider()) {
            body.put("enable_thinking", false);
        }
    }

    /** 流式讲解 / 问答：开启思考，便于读取 reasoning 展示。其余 provider 不加参数。
     *  同时把 reasoning_effort 压到 low，让思考更短、生成更快（DeepSeek 默认 high、OpenAI 默认中高）；
     *  不认识的 provider 不加该参数，避免未知参数 400（即使加了也会被去参兜底剥离）。 */
    private void applyThinkingStream(Map<String, Object> body) {
        if (isThinkingParamProvider()) {
            body.put("thinking", Map.of("type", "enabled"));
            body.put("reasoning_effort", STREAM_REASONING_EFFORT);
        } else if (isQwenThinkingProvider()) {
            body.put("enable_thinking", true);
        } else if (isOpenAiReasoning()) {
            // OpenAI 推理模型无 thinking/{type} 参数，用 reasoning_effort 控制思考深度
            body.put("reasoning_effort", STREAM_REASONING_EFFORT);
        }
    }

    /**
     * token 上限适配：OpenAI 推理模型（o1/o3/o4、gpt-5）用 {@code max_completion_tokens} 且不支持 temperature；
     * 其余一律 {@code max_tokens}。避免 "Unknown parameter: max_tokens" 这类 400。
     */
    /** 是否 OpenAI 推理模型（o1/o3/o4 / gpt-5）：用 max_completion_tokens，且可用 reasoning_effort 控制思考深度。 */
    private boolean isOpenAiReasoning() {
        String m = modelLower();
        return m.matches("^o[134](-|$).*") || m.matches("^gpt-5.*");
    }

    private void applyDefaultTokens(Map<String, Object> body) {
        String m = modelLower();
        boolean openAiReasoning = m.matches("^o[134](-|$).*") || m.matches("^gpt-5.*");
        if (openAiReasoning) {
            body.put("max_completion_tokens", 8192);
            body.remove("temperature");
        } else {
            body.put("max_tokens", 8192);
        }
    }

    /** 流式思考模式：给足输出额度，避免 reasoning_content 抢占正文预算导致正文为空/被截断。 */
    private void applyGenerousBudget(Map<String, Object> body) {
        if (body.containsKey("max_completion_tokens")) {
            body.put("max_completion_tokens", 16384);
        } else {
            body.put("max_tokens", 16384);
        }
    }

    private boolean hasOptionalParams(Map<String, Object> body) {
        return body.keySet().stream().anyMatch(OPTIONAL_KEYS::contains);
    }

    /** 保留 model/messages/stream 等必要字段，丢掉可能触发 400 的可选参数。 */
    private Map<String, Object> stripOptionalParams(Map<String, Object> body) {
        Map<String, Object> minimal = new java.util.HashMap<>(body);
        minimal.keySet().removeAll(OPTIONAL_KEYS);
        return minimal;
    }

    /** 同步 POST：失败若是 400 且带可选参数，则去参重试一次（兼容不认识 thinking/max_tokens 的 provider）。 */
    private String postWithRetry(Map<String, Object> body) throws Exception {
        try {
            return postTo(endpoint(), body, 60);
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("LLM HTTP 400")
                    && hasOptionalParams(body)) {
                log.info("complete HTTP 400（可能不认识可选参数），去掉可选参数后重试: {}", e.getMessage());
                return postTo(endpoint(), stripOptionalParams(body), 60);
            }
            throw e;
        }
    }

    /** 流式 POST：发送一次；400 且带可选参数时去参重试。返回 null 表示应停止（连接失败等）。 */
    private HttpResponse<InputStream> sendStreamWithRetry(Map<String, Object> body, Consumer<Throwable> onError) {
        Map<String, Object> reqBody = body;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                HttpRequest request = buildStreamRequest(reqBody);
                HttpResponse<InputStream> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() == 400 && attempt == 0 && hasOptionalParams(reqBody)) {
                    log.info("stream HTTP 400（可能不认识可选参数），去掉可选参数后重试");
                    reqBody = stripOptionalParams(reqBody);
                    continue;
                }
                return response;
            } catch (Throwable t) {
                if (attempt == 0 && hasOptionalParams(reqBody)) {
                    log.info("stream IO 异常（{}），去掉可选参数后重试", t.getMessage());
                    reqBody = stripOptionalParams(reqBody);
                    continue;
                }
                notifyError(onError, t);
                return null;
            }
        }
        return null;
    }

    private HttpRequest buildStreamRequest(Map<String, Object> body) throws Exception {
        return HttpRequest.newBuilder()
                .uri(URI.create(endpoint()))
                .header("Authorization", "Bearer " + cfg().apiKey())
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .timeout(Duration.ofSeconds(3600))   // 首字节前的等待可因模型先思考而不流式泄露而很长；放宽到 1 小时，与 nginx/Tomcat 对齐
                .build();
    }

    /**
     * 从一个 delta / message / choice 节点取出思考片段：兼容所有 OpenAI 兼容格式的常见字段名。
     * 思考型模型可能用 reasoning_content / reasoning / reasoning_text / reasoning_details /
     * reasoning_summary / chain_of_thought（字符串、数组、或 {text}/{content}/{value}/{summary} 对象）。
     * 非思考型模型没有这些字段，原样返回 null，不影响 content 正文。
     */
    private static String firstReasoningToken(JsonNode node) {
        String[] fields = {"reasoning_content", "reasoning", "reasoning_text",
                "reasoning_details", "reasoning_summary", "chain_of_thought"};
        for (String f : fields) {
            JsonNode n = node.get(f);
            if (n == null) continue;
            String s = reasonText(n);
            if (s != null && !s.isEmpty()) return s;
        }
        return null;
    }

    /** 递归抽取一个 reasoning 节点里的正文：字符串 / 数组逐项拼接 / 对象取 text/content/value/summary。 */
    private static String reasonText(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        if (n.isTextual()) return n.asText();
        if (n.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : n) {
                String t = reasonText(item);
                if (t != null && !t.isEmpty()) sb.append(t);
            }
            return sb.length() == 0 ? null : sb.toString();
        }
        if (n.isObject()) {
            // OpenAI 兼容常见的嵌套形态：{text} / {content} / {value} / {summary}
            for (String k : new String[]{"text", "content", "value", "summary"}) {
                String t = reasonText(n.get(k));
                if (t != null && !t.isEmpty()) return t;
            }
        }
        return null;
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
