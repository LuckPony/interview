package interview.homegrown.modules.knowledge.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.homegrown.common.ai.LlmRawClient;
import interview.homegrown.common.result.Result;
import interview.homegrown.common.exception.BusinessException;
import interview.homegrown.common.exception.ErrorCode;
import interview.homegrown.common.web.SseWriter;
import interview.homegrown.modules.knowledge.service.ChatAttachmentService;
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

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.web.multipart.MultipartFile;
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
    private final ChatAttachmentService attachments;

    public KnowledgeController(CardService cardService,
                               ChatCaptureService chatCaptureService,
                               LlmRawClient rawClient, ChatAttachmentService attachments) {
        this.cardService = cardService;
        this.chatCaptureService = chatCaptureService;
        this.rawClient = rawClient;
        this.attachments = attachments;
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
    @PostMapping(value = "/attachments/parse", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<ChatAttachmentService.Attachment> parseAttachment(@RequestParam("file") MultipartFile file) throws IOException {
        return Result.success(attachments.parse(file));
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

        String prompt = buildUserPrompt(req); // 进入异步线程前校验，错误可作为普通 JSON 返回。
        StreamingResponseBody body = out -> {
            try (SseWriter writer = new SseWriter(out)) {
                writer.write("event: status\ndata: {\"text\":\"已接收问题，正在准备回答…\"}\n\n");
                AtomicReference<Throwable> failure = new AtomicReference<>();
                AtomicBoolean hasAnswer = new AtomicBoolean();
                AtomicBoolean thinkingNotified = new AtomicBoolean();
                rawClient.stream(systemPrompt, prompt,
                        token -> {
                            if (!token.isBlank()) hasAnswer.set(true);
                            writeFrame(writer, "data: {\"text\":" + jsonEscape(token) + "}\n\n");
                        },
                        failure::set, false,
                        reasoning -> {
                            if (thinkingNotified.compareAndSet(false, true)) {
                                writeFrame(writer, "event: status\ndata: {\"text\":\"正在分析问题与代码，可随时停止…\"}\n\n");
                            }
                        });
                if (failure.get() != null) {
                    log.warn("知识问答生成失败", failure.get());
                    writer.write("event: error\ndata: {\"message\":" + jsonEscape(friendlyError(failure.get())) + "}\n\n");
                } else if (!hasAnswer.get()) {
                    writer.write("event: error\ndata: {\"message\":\"模型未返回正文，请重试或在设置中降低思考强度。\"}\n\n");
                } else {
                    writer.write("event: done\ndata: {}\n\n");
                }
            } catch (IOException | UncheckedIOException disconnected) {
                log.debug("知识问答连接已关闭: {}", disconnected.getMessage());
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
        if (question.isBlank()) throw new BusinessException(ErrorCode.BAD_REQUEST, "请输入问题或添加文件");
        if (question.length() > 60000) throw new BusinessException(ErrorCode.BAD_REQUEST, "问题与附件文字合计不能超过 60000 字符，请拆分为几轮提问");
        List<Msg> history = req.conversation() == null ? List.of() : req.conversation();
        if (history.isEmpty()) return question;

        int fromIndex = Math.max(0, history.size() - 12);
        List<String> lines = new ArrayList<>();
        int remaining = 18000;
        for (int index = history.size() - 1; index >= fromIndex && remaining > 0; index--) {
            Msg message = history.get(index);
            if (message == null || message.content() == null || message.content().isBlank()) continue;
            String role = "ai".equalsIgnoreCase(message.role()) ? "AI" : "用户";
            String content = message.content().trim();
            int limit = Math.min(6000, remaining);
            if (content.length() > limit) {
                String suffix = "…（较早上下文已节选）";
                content = content.substring(0, Math.max(0, limit - suffix.length())) + suffix;
            }
            remaining -= content.length();
            lines.add(0, role + "：" + content);
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
    private static void writeFrame(SseWriter writer, String frame) {
        try {
            writer.write(frame);
        } catch (IOException disconnected) {
            throw new UncheckedIOException(disconnected);
        }
    }

    static String friendlyError(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedException || cause instanceof InterruptedIOException
                    || cause instanceof TimeoutException) {
                return "本轮连接超时或已中断，已保留问题与已生成内容，请重试；较长代码可拆分提问。";
            }
        }
        String message = error.getMessage() == null ? "" : error.getMessage();
        if (message.contains("尚未配置 API Key")) return "尚未配置 API Key，请先到设置页配置模型。";
        if (message.contains("401") || message.contains("403")) return "模型服务拒绝访问，请检查 API Key 和模型使用权限。";
        if (message.contains("429")) return "模型服务暂时繁忙或额度不足，请检查账户额度后重试。";
        if (message.contains("400") || message.contains("413")) return "模型拒绝了本次请求，请检查模型配置，或缩短问题与附件内容后重试。";
        return "模型服务连接失败，已保留当前内容，请稍后重试或检查模型服务地址。";
    }
}




