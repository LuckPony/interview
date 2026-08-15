package interview.homegrown.modules.drill.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.homegrown.modules.drill.service.StudyPlanService;
import interview.homegrown.modules.drill.service.ConceptValidationService;
import interview.homegrown.modules.drill.web.dto.ChatMessage;
import interview.homegrown.modules.drill.web.dto.IntakeResponse;
import interview.homegrown.modules.drill.web.dto.PlanView;
import interview.homegrown.modules.drill.web.dto.StudyPlanDraft;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/**
 * 入口：学习方向（study_plan）的增与列。
 *
 * <ul>
 *   <li>POST /study-plan/intake —— 无状态多轮对话，返回 {reply, draft}</li>
 *   <li>POST /study-plan/confirm —— 把 draft 落库（clamp layer、同名幂等）</li>
 *   <li>GET  /study-plan        —— 列出我的方向（含知识点与精熟计数）</li>
 *   <li>PUT/DELETE /study-plan/{id} —— 编辑标题/目标、删除方向（用户自主权）</li>
 *   <li>POST /study-plan/{id}/concepts、PUT/DELETE /study-plan/concepts/{id} —— 增改删知识点</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/study-plan")
public class StudyPlanController {

    private final StudyPlanService service;
    private final ObjectMapper objectMapper;
    private final ConceptValidationService validationService;

    public StudyPlanController(StudyPlanService service, ObjectMapper objectMapper,
                               ConceptValidationService validationService) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.validationService = validationService;
    }

    @PostMapping("/intake")
    public IntakeResponse intake(@RequestBody IntakeRequest req) {
        return service.intake(req.messages(), req.corpusId());
    }

    /**
     * 流式 intake（SSE）：先逐 token 推对话回复，结束后 event:draft 推草稿（信息不够则 null）。
     * 与练习聊天同款流式体验。
     */
    @PostMapping(value = "/intake/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> intakeStream(@RequestBody IntakeRequest req) {
        List<ChatMessage> messages = req.messages();
        Long corpusId = req.corpusId();
        StreamingResponseBody body = out -> {
            try {
                String reply = service.intakeStream(messages, corpusId, token -> {
                    try {
                        out.write(("data: " + objectMapper.writeValueAsString(Map.of("text", token)) + "\n\n")
                                .getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    } catch (Exception ignored) {
                        // 单个 token 推送失败不致命
                    }
                });
                // 把流式的回复并入对话历史，再提取草稿
                List<ChatMessage> conv = new ArrayList<>(messages);
                if (reply != null && !reply.isBlank()) {
                    conv.add(new ChatMessage("assistant", reply));
                }
                StudyPlanDraft draft = service.normalizeDraftForView(service.extractDraft(conv, corpusId));
                out.write(("event: draft\ndata: " + objectMapper.writeValueAsString(draft) + "\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
                out.write("event: done\ndata: {}\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (Exception e) {
                try {
                    out.write(("event: error\ndata: " + objectMapper.writeValueAsString(
                            Map.of("message", String.valueOf(e.getMessage()))) + "\n\n")
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

    @PostMapping("/validate-candidates")
    public ConceptValidationService.ValidationResponse validateCandidates(@RequestBody ValidateRequest req) {
        return validationService.validate(req.draft());
    }

    @PostMapping("/confirm")
    public PlanView confirm(@RequestBody ConfirmRequest req) {
        return service.confirm(currentUserId(), req.draft());
    }

    @GetMapping
    public List<PlanView> list() {
        return service.list(currentUserId());
    }

    // ---------------------------------------------------- 用户手动编辑（自主权）

    @PutMapping("/{planId}")
    public PlanView updatePlan(@PathVariable Long planId, @RequestBody UpdatePlanRequest req) {
        return service.updatePlan(currentUserId(), planId, req.title(), req.goal());
    }

    @DeleteMapping("/{planId}")
    public Map<String, Object> deletePlan(@PathVariable Long planId) {
        service.deletePlan(currentUserId(), planId);
        return Map.of("ok", true);
    }

    @PostMapping("/{planId}/concepts")
    public PlanView addConcept(@PathVariable Long planId, @RequestBody ConceptWriteRequest req) {
        return service.addConcept(currentUserId(), planId, req.name(), req.layer(), req.note());
    }

    @PutMapping("/concepts/{conceptId}")
    public PlanView updateConcept(@PathVariable Long conceptId, @RequestBody ConceptWriteRequest req) {
        return service.updateConcept(currentUserId(), conceptId, req.name(), req.layer(), req.note());
    }

    @DeleteMapping("/concepts/{conceptId}")
    public Map<String, Object> deleteConcept(@PathVariable Long conceptId) {
        service.deleteConcept(currentUserId(), conceptId);
        return Map.of("ok", true);
    }

    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Long)) {
            throw new ResponseStatusException(UNAUTHORIZED, "未鉴权");
        }
        return (Long) auth.getPrincipal();
    }

    public record IntakeRequest(List<ChatMessage> messages, Long corpusId) {}
    public record ValidateRequest(StudyPlanDraft draft) {}
    public record ConfirmRequest(StudyPlanDraft draft) {}
    public record UpdatePlanRequest(String title, String goal) {}
    public record ConceptWriteRequest(String name, Integer layer, String note) {}
}
