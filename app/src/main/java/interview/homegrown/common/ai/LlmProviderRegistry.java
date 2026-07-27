package interview.homegrown.common.ai;


import interview.homegrown.common.config.AiConfigProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM Provider 注册中心
 * 职责：
 * 1. 在应用启动时根据配置创建多个 Provider 的 ChatClient 实例
 * 2. 对外提供 getChatClientOrDefault(name) 统一获取入口
 * 3. 运行时动态刷新（后续阶段扩展）
 * 使用示例：
 *   ChatClient client = registry.getChatClientOrDefault("deepseek");
 *   String reply = client.prompt().user("你好").call().content();
 */

@Component
public class LlmProviderRegistry {
    private static final Logger log = LoggerFactory.getLogger(LlmProviderRegistry.class);

    private final AiConfigProperties config;
    private final Map<String, ChatClient> clients = new ConcurrentHashMap<>();

    public LlmProviderRegistry(AiConfigProperties config) {
        this.config = config;
    }

    //启动时初始化所有可用的Provider
    @PostConstruct
    public void init() {
        var providers = config.getProviders();
        var structured = config.getStructured();
        if (providers == null || providers.isEmpty()) {
            log.warn("未配置任何AI Provider,LLM功能不可用");
            return;
        }

        for(var entry:providers.entrySet()) {
            String name = entry.getKey();
            var cfg =  entry.getValue();
            if(!cfg.isAvailable()){
                log.info("Provider [{}] 配置不完整，跳过（缺少 baseUrl/apiKey/model）", name);
                continue;
            }
            try{
                OpenAiChatOptions options = OpenAiChatOptions.builder()
                        .baseUrl(cfg.getBaseUrl())
                        .apiKey(cfg.getApiKey())
                        .model(cfg.getModel())
                        .temperature(cfg.getTemperature())
                        .timeout(Duration.ofSeconds(60))
                        .maxRetries(structured.getMaxAttempts())
                        .build();
                OpenAiChatModel chatModel = OpenAiChatModel.builder()
                        .options(options)
                        .build();
                ChatClient chatClient = ChatClient.builder(chatModel).build();
                clients.put(name,chatClient);
                log.info("AI Provider [{}] 初始化成功: model={}, baseUrl={}",
                        name, cfg.getModel(), cfg.getBaseUrl());

            }catch (Exception e){
                log.error("AI Provider [{}] 初始化失败: {}", name, e.getMessage());
            }

        }

        if(clients.isEmpty()){
            log.warn("没有可用的 AI Provider，请检查配置");
        }else{
            log.info("AI Provider 注册完成，可用：{}", clients.keySet());
        }

    }

    /**
     * 获取指定名称的 ChatClient
     *
     * @param provider Provider 名称，传入 null 或空串则返回默认
     * @return ChatClient 实例
     * @throws IllegalStateException 没有可用的 Provider
     */
    public ChatClient getChatClientOrDefault(String provider){

        if(provider==null || provider.isEmpty()){
            log.warn("没有提供Provider，将使用默认Provider继续");
            provider = config.getDefaultProvider();
        }

        ChatClient client = clients.get(provider);
        if(client!=null){
            return client;
        }

        //如果指定的不存在，回退到默认
        if (!provider.equals(config.getDefaultProvider())) {
            log.warn("Provider [{}] 不存在，回退到默认 [{}]", provider, config.getDefaultProvider());
            client = clients.get(config.getDefaultProvider());
        }
        if (client==null){
            throw new IllegalStateException("没有可用的 AI Provider，请检查 API Key 配置");
        }
        return client;

    }

    //获取已经注册的Provider名称
    public Map<String, ChatClient> getAllClients() {
        return Map.copyOf(clients);
    }


}
