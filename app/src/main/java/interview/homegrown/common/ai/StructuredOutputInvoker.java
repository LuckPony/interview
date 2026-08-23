package interview.homegrown.common.ai;


import interview.homegrown.common.config.AiConfigProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * 结构化输出调用器
 *
 * 职责：
 * 1. 向 LLM 发送提示词，要求以 JSON 格式返回
 * 2. 使用 BeanOutputConverter 将 JSON 解析为 Java 对象
 * 3. 解析失败时自动重试，并注入错误信息帮助模型修正
 * 适用场景：
 * - 简历分析结果解析
 * - 面试评估评分
 * - 题目生成
 * - 任何需要 LLM 返回结构化数据的场景
 */

@Component
public class StructuredOutputInvoker {

    private static final Logger log = LoggerFactory.getLogger(StructuredOutputInvoker.class);

    private final LlmProviderRegistry registry;
    private final AiConfigProperties config;
    private final LlmRawClient rawClient;

    public StructuredOutputInvoker(LlmProviderRegistry registry, AiConfigProperties config,
                                  LlmRawClient rawClient) {
        this.registry = registry;
        this.config = config;
        this.rawClient = rawClient;
    }

    /**
     * 调用 LLM 并获取结构化输出
     *
     * @param systemPrompt 系统提示词（定义角色和输出格式）
     * @param userPrompt   用户提示词（输入的具体内容）
     * @param outputClass  期望的输出类型
     * @param provider     Provider 名称（null 则使用默认）
     * @param <T>          输出类型
     * @return 解析后的结构化对象
     */
    public <T> T invoke(String systemPrompt, String userPrompt, Class<T> outputClass, String provider){

        var converter = new BeanOutputConverter<>(outputClass);

        // 注册中心只持有服务器级 key（本地模式 = .env 的 API_KEY）。
        // 云端后端故意不配服务器级 key（每个用户用自己的），registry 为空是常态——
        // 此时不能直接抛错，改走 LlmRawClient（当前用户/请求头 X-LLM-Key 里的 key）。
        ChatClient client = null;
        try {
            client = registry.getChatClientOrDefault(provider);
        } catch (IllegalStateException e) {
            log.warn("注册中心无可用 Provider（云端无服务器级 key），改用用户配置的直连客户端: {}", e.getMessage());
        }

        // 两条通道都不可用（用户也未配置 key）时提前给明确报错，避免走到「LLM 返回为空」误导排查。
        if (client == null && (rawClient == null || !rawClient.availableForCurrentRequest())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "尚未配置 API Key，请到「设置」页填写后重试");
        }

        int maxAttempts = config.getStructured().getMaxAttempts();
        boolean includeError = config.getStructured().isIncludeLastError();

        //构造带格式说明的 system prompt
        String formatInstruction = converter.getFormat();
        String effectiveSystem = systemPrompt + "\n\n" + formatInstruction;

        String lastError = null;

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try{

                String currentUser = userPrompt;
                if(attempt > 0 && includeError && lastError != null){

                    //重试时注入错误信息，帮助模型修正输出
                    currentUser = "上次解析出错，请修正：\n错误信息：" + lastError +
                            "\\n\\n---\\n原始请求：\\n" + userPrompt;
                    log.info("结构化输出重试第 {} 次, 上次错误: {}", attempt + 1, lastError);
                }

                // 优先 Spring AI（官方适配器，换模型更通用）；Spring AI 失败/返回空再走原生兜底。
                String response = rawOrSpring(client, effectiveSystem, currentUser);

                if (response == null || response.isBlank()){
                    String effectiveProvider = (provider != null && !provider.isBlank())
                            ? provider : config.getDefaultProvider();
                    var cfg = config.getProviders().get(effectiveProvider);
                    log.warn("LLM 返回空 content (provider={}, model={})。若使用推理模型（如 deepseek-v4-flash），"
                                    + "可能是模型只输出了 reasoning_content，Spring AI 2.0 未解析该字段。",
                            effectiveProvider, cfg != null ? cfg.getModel() : "unknown");
                    throw new RuntimeException("LLM 返回为空");
                }

                T result = converter.convert(extractJson(response));
                log.debug("结构化输出解析成功, 类型={}",outputClass.getSimpleName());
                return result;
            }catch(Exception e){
                lastError = e.getMessage();
                log.warn("结构化输出解析失败 (attempt {} / {}: {})", attempt + 1, maxAttempts, lastError);
                if(attempt >= maxAttempts-1){
                    throw new RuntimeException("结构化输出解析失败（已尝试 " + maxAttempts + " 次），请重试或换一种描述");
                }
            }
        }
        //到达不了这里，只是为了语法必须添加返回值
        throw new RuntimeException("结构化输出调用异常，请重试");
    }

    //使用默认 Provider 的简化调用
    public <T> T invoke(String systemPrompt, String userPrompt, Class<T> outputClass){
        return invoke(systemPrompt, userPrompt, outputClass, null);
    }

    /**
     * 取一次模型回复：优先 LlmRawClient 原生直连（读 reasoning_content，对推理模型可靠且快）。
     * Spring AI 读不到 deepseek 的 reasoning_content（私有字段）会抛 "Error reading response"，
     * 若先走它 = 每次白等一次再兜底，出题翻倍变慢；故原生优先，Spring AI 仅作原生不可用时的后备。
     */
    private String rawOrSpring(ChatClient client, String system, String user) {
        if (rawClient != null) {
            String r = rawClient.complete(system, user);
            if (r != null && !r.isBlank()) return r;
        }
        // registry 为空（云端）+ rawClient 不可用/失败时：不再走 Spring AI，直接判空由上层重试/抛错
        if (client == null) return null;
        try {
            return client.prompt().system(system).user(user).call().content();
        } catch (Exception e) {
            log.warn("Spring AI 调用异常: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从模型原始输出中清洗出 JSON：
     * - 去掉 ```json ... ``` / ``` ... ``` 代码围栏（推理模型常用）
     * - 截取第一个 '{' 到最后一个 '}' 之间内容，容忍前后多余的说明文字
     * 目的：让 BeanOutputConverter 首轮即可解析成功，避免解析失败触发重试
     * （每次重试都会让推理模型再跑一遍推理，深度推理模型会把耗时翻倍）。
     */
    private String extractJson(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl >= 0) t = t.substring(nl + 1);
            int fence = t.lastIndexOf("```");
            if (fence >= 0) t = t.substring(0, fence);
            t = t.trim();
        }
        int s = t.indexOf('{');
        int e = t.lastIndexOf('}');
        if (s >= 0 && e > s) t = t.substring(s, e + 1);
        return t;
    }

}
