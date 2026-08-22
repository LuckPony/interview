package interview.homegrown.modules.knowledge.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.homegrown.common.ai.LlmProviderRegistry;
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
    private final LlmProviderRegistry llmProviderRegistry;

    public KnowledgeController(CardService cardService,
                               ChatCaptureService chatCaptureService,
                               LlmProviderRegistry llmProviderRegistry) {
        this.cardService = cardService;
        this.chatCaptureService = chatCaptureService;
        this.llmProviderRegistry = llmProviderRegistry;
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
            try {
                var client = llmProviderRegistry.getChatClientOrDefault(req.provider());
                client.prompt()
                        .system(systemPrompt)
                        .user(req.question())
                        .stream()
                        .content()
                        .doOnNext(token -> {
                            try {
                                String frame = "data: {\"text\":" + jsonEscape(token) + "}\n\n";
                                out.write(frame.getBytes(StandardCharsets.UTF_8));
                                out.flush();
                            } catch (Exception e) {
                                log.debug("ask token 推送异常（已吞）: {}", e.getMessage());
                            }
                        })
                        .blockLast();

                out.write("event: done\ndata: {}\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
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




