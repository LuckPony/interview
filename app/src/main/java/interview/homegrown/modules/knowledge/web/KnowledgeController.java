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
import java.util.ArrayList;
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

    public record Msg(String role, String content) {}
    public record AskRequest(String question, String provider, List<Msg> conversation) {}
    public record CaptureRequest(List<Msg> conversation) {}
    public record UpdateRequest(String question, String answer, String tags, Long planId, String detail) {}
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
        return Result.success(cardService.update(uid(), id, req.question(), req.answer(), req.tags(), req.planId(), req.detail()));
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
                你是一个知识问答助手，回答要完整、有深度、结构清晰。
                要求：
                1. 直接给出答案，不要输出任何字段标签或前缀（如 question:、answer:、tags:、问题：、回答：等一律禁止）。
                2. 用 Markdown 组织内容：要点分条、关键术语加粗、涉及代码时用代码块、涉及对比时用表格，让回答可读性强。
                3. 内容要有干货：先给结论/定义，再讲原理或推理过程，补一个具体例子，最后给易错点或延伸建议。
                4. 不确定的地方明确说明，不要编造。
                5. 当前问题如果是追问，必须结合提供的本会话历史理解“它”“这个”“上面”等指代。
                6. 任何多行代码、配置或命令都必须使用带语言标识的 Markdown 三反引号代码块。
                如果是闲聊或无需长期保存的话题，正常简短回应即可，同样不要输出字段标签。""";

        StreamingResponseBody body = out -> {
            // 注意：这里不能走 LlmProviderRegistry 的静态 ChatClient —— 它由启动配置（application.yml）
            // 构建，而项目已明确「不配服务器级/共享 key」（providers 的 api-key 全空），注册中心为空会直接
            // 抛“没有可用的 AI Provider”，导致自由问答永远失败。必须走 LlmRawClient 原生直连：
            // 按请求解析 key（请求头 X-LLM-Key > 当前用户设置 > 启动配置），与讲解/出题/判分等其它流式端点一致。
            final String[] streamError = {null};
            try {
                rawClient.stream(systemPrompt, buildUserPrompt(req),
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

    /**
     * 自由问答客户端目前是无状态的，因此把最近六轮对话显式放进本次用户提示。
     * 历史只用于理解指代与追问，当前问题始终单独放在末尾，避免模型把旧问题当成本轮任务。
     */
    static String buildUserPrompt(AskRequest req) {
        String question = req.question() == null ? "" : req.question().trim();
        List<Msg> history = req.conversation() == null ? List.of() : req.conversation();
        if (history.isEmpty()) return question;

        int fromIndex = Math.max(0, history.size() - 12);
        List<String> lines = new ArrayList<>();
        for (Msg message : history.subList(fromIndex, history.size())) {
            if (message == null || message.content() == null || message.content().isBlank()) continue;
            String role = "ai".equalsIgnoreCase(message.role()) ? "AI" : "用户";
            String content = message.content().trim();
            if (content.length() > 3000) content = content.substring(0, 3000) + "…";
            lines.add(role + "：" + content);
        }
        if (lines.isEmpty()) return question;

        return """
                以下是当前会话最近的对话记录。请结合它理解指代、省略和追问，但以最后的当前问题为本轮回答目标。

                <conversation>
                %s
                </conversation>

                当前问题：%s
                """.formatted(String.join("\n\n", lines), question).trim();
    }
    }




