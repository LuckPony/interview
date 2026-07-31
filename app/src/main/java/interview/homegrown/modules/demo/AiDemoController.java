package interview.homegrown.modules.demo;


import interview.homegrown.common.ai.LlmProviderRegistry;
import interview.homegrown.common.ai.StructuredOutputInvoker;
import interview.homegrown.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * AI 功能演示 Controller
 * 验证：
 * 1. Provider 注册中心是否正常工作
 * 2. 能否成功调用 LLM
 * 3. 结构化输出是否正常工作
 */
@RestController
@RequestMapping("/api/demo/ai")
@Tag(name = "AI能力演示", description = "验证 Provider 注册、文本对话、结构化输出")
public class AiDemoController {

    private final LlmProviderRegistry registry;
    private final StructuredOutputInvoker invoker;

    public AiDemoController(LlmProviderRegistry registry, StructuredOutputInvoker invoker) {
        this.registry = registry;
        this.invoker = invoker;
    }

    //查看所有已注册的Provider
    @GetMapping("/providers")
    @Operation(summary = "查看已注册Providers", description = "列出所有配置成功的AI Providers")
    public Result<Map<String, Object>> listProviders(){
        var clients = registry.getAllClients();
        var info = Map.of(
                "available",clients.keySet(),
                "count",clients.size()
        );
        return Result.success(info);
    }

    /**
     * 简单的文本对话测试,测试AI注册接口是否实现
     * @param request 包含 provider 和 message 的请求体
     */
    @PostMapping("/chat")
    @Operation(summary = "文本对话测试", description = "调用指定 Provider 完成一次文本对话")
    public Result<ChatResponse> chat(
            @Parameter(description = "请求体：message 为消息内容，provider 为模型名称（留空用默认）")
            @RequestBody ChatRequest request){
        var client = registry.getChatClientOrDefault(request.provider());
        String reply = client.prompt()
                .system("你是一个知识渊博、乐于助人的AI助手。请用中文回答。")
                .user(request.message())
                .call()
                .content();
        return Result.success(new ChatResponse(reply,request.provider()));
    }

    //结构化输出演示----分析一句话属于哪种情绪，测试AI调用接口是否实现
    @PostMapping("/analyze-sentiment")
    @Operation(summary = "情绪分析", description = "调用指定的 Provider 完成一次情绪分析")
    public Result<SentimentResponse> analyzeSentiment(
            @Parameter(description = "请求体：message 为消息内容，provider 为模型名称（留空用默认）")
            @RequestBody ChatRequest request){
        String systemPrompt = "你是一个情绪分析师。请分析用户输入的情绪倾向，以 JSON 格式返回。";
        SentimentResponse result = invoker.invoke(
                systemPrompt,
                request.message(),
                SentimentResponse.class,
                request.provider()
        );
        return Result.success(result);
    }

    //用Record创建前面Result T属性的返回类信息
    public record ChatRequest(String message, String provider) {}

    public record ChatResponse(String reply, String provider) {}
    public record SentimentResponse(
                String sentiment,
                double positiveScore,
                double negativeScore,
                String analysis
    ){}
}
