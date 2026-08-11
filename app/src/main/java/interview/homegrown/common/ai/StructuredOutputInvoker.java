package interview.homegrown.common.ai;


import interview.homegrown.common.config.AiConfigProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;

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
    private final DeepSeekRawClient rawClient;

    public StructuredOutputInvoker(LlmProviderRegistry registry, AiConfigProperties config,
                                  DeepSeekRawClient rawClient) {
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
        ChatClient client = registry.getChatClientOrDefault(provider);

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

                String response = null;
                try {
                    response = client.prompt()
                            .system(effectiveSystem)
                            .user(currentUser)
                            .call()
                            .content();
                } catch (Exception callEx) {
                    // Spring AI 读不到 content（推理模型常把答案塞进 reasoning_content 而抛
                    // "Error reading response"），不能直接放弃——改用原生接口兜底。
                    log.warn("Spring AI 调用异常，改用 DeepSeek 原生兜底: {}", callEx.getMessage());
                }

                // 兜底：Spring AI 只读了 content，推理模型偶发把答案塞进 reasoning_content 导致 content 为空。
                // 这里直连 DeepSeek 原生接口，content 为空时改用 reasoning_content 作为兜底，避免无谓 500。
                if ((response == null || response.isBlank()) && rawClient != null) {
                    String fallback = rawClient.complete(effectiveSystem, currentUser);
                    if (fallback != null && !fallback.isBlank()) {
                        log.info("已用 DeepSeek 原生 reasoning_content 兜底");
                        response = fallback;
                    }
                }

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
                    throw new RuntimeException("结构化输出解析失败, 已重试" + maxAttempts);
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
