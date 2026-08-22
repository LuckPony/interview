package interview.homegrown.modules.knowledge.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.homegrown.common.ai.LlmRawClient;
import interview.homegrown.common.result.Result;
import interview.homegrown.modules.knowledge.domain.KnowledgeCard;
import interview.homegrown.modules.knowledge.service.CardService;
import interview.homegrown.modules.knowledge.service.ChatCaptureService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeController.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private final CardService cardService;
    private final ChatCaptureService chatCaptureService;
    private final LlmRawClient rawClient;

    public KnowledgeController(CardService cardService,
                               ChatCaptureService chatCaptureService,
                               LlmRawClient rawClient) {
        this.cardService = cardService;
        this.chatCaptureService = chatCaptureService;
        this.rawClient = rawClient;
    }

    private Long uid() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (Long) auth.getPrincipal();
    }

    //定义记录和函数
    private static String jsonEscape(String s) {
        try {
            return mapper.writeValueAsString(s == null ? "" : s);
        } catch (Exception e) {
            return "\"\"";
        }
    }

    public record AskRequest(String question, String provider) {}
    public record CaptureRequest(List<Msg> conversation) { public record Msg(String role, String content) {} }
    public record UpdateRequest(String question, String answer, String tags, Long planId) {}
    public record ReviewRequest(boolean mastered) {}

    // ==================== 卡片 CRUD ====================
    @PostMapping("/capture")
    public Result<KnowledgeCard> capture(@RequestBody CaptureRequest req) {
        var messages = req.conversation().stream()
                .map(m -> new ChatCaptureService.Message(m.role(), m.content()))
                .toList();
        return Result.success(chatCaptureService.capture(uid(), messages));
    }

    @GetMapping("/cards")
    public Result<List<KnowledgeCard>> list(@RequestParam(required = false) Long planId) {
        return Result.success(cardService.list(uid(), planId));
    }

    @GetMapping("/cards/due")
    public Result<List<KnowledgeCard>> due() {
        return Result.success(cardService.due(uid()));
    }

    @PutMapping("/cards/{id}")
    public Result<KnowledgeCard> update(@PathVariable Long id, @RequestBody UpdateRequest req) {
        return Result.success(cardService.update(uid(), id, req.question(), req.answer(), req.tags(), req.planId()));
    }

    @PostMapping("/cards/{id}/review")
    public Result<KnowledgeCard> review(@PathVariable Long id, @RequestBody ReviewRequest req) {
        return Result.success(cardService.review(uid(), id, req.mastered()));
    }

    @DeleteMapping("/cards/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        cardService.delete(uid(), id);
        return Result.success();
    }
    // ==================== 自由问答（SSE 流式） ====================
    @PostMapping(value = "/ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> ask(@RequestBody AskRequest req){

        String systemPrompt = """
                你是一个知识沉淀过滤器。请客观判断下面这段对话：
                1. 如果对话中有值得长期回顾的知识点（如某个技术原理、解决方案、易错点、关键结论），
                   提炼成一张知识卡片：question（一句话问题/要点）、answer（精简回答，1-3句）、tags（2-4个标签）。
                2. 如果只是闲聊、寒暄、一次性事务（如'今天天气''帮我算个账'），
                   则返回 question 为空字符串、answer 为空、tags 为空数组。
                不要为了生成卡片而强行提炼，没有价值就明确返回空。""";

        StreamingResponseBody body = out -> {
            // 注意：这里不能走 LlmProviderRegistry 的静态 ChatClient —— 它由启动配置（application.yml）
            // 构建，而项目已明确「不配服务器级/共享 key」（providers 的 api-key 全空），注册中心为空会直接
            // 抛“没有可用的 AI Provider”，导致自由问答永远失败。必须走 LlmRawClient 原生直连：
            // 按请求解析 key（请求头 X-LLM-Key > 当前用户设置 > 启动配置），与讲解/出题/判分等其它流式端点一致。
            final String[] streamError = {null};
            try {
                rawClient.stream(systemPrompt, req.question(),
                        token -> {
                            try {
                                String frame = "data: {\"text\":" + jsonEscape(token) + "}\n\n";
                                out.write(frame.getBytes(StandardCharsets.UTF_8));
                                out.flush();
                            } catch (Exception e) {
                                log.debug("ask token 推送异常（已吞）: {}", e.getMessage());
                            }
                        },
                        err -> streamError[0] = err == null ? "LLM 调用失败" : err.getMessage(),
                        /* fallbackToReasoning */ true,
                        /* onReasoning */ null);

                if (streamError[0] == null) {
                    out.write("event: done\ndata: {}\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } else {
                    out.write(("event: error\ndata: {\"message\":" + jsonEscape(streamError[0]) + "}\n\n")
                            .getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            } catch (Exception e) {
                log.warn("ask SSE 异常", e);
                try {
                    out.write(("event: error\ndata: {\"message\":" + jsonEscape(e.getMessage()) + "}\n\n")
                            .getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (Exception ignored) {
                }
            }
        };

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(body);
    }
    }




