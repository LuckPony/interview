package interview.homegrown.modules.drill.web;

import interview.homegrown.modules.drill.service.StudyPlanService;
import interview.homegrown.modules.drill.web.dto.ChatMessage;
import interview.homegrown.modules.drill.web.dto.IntakeResponse;
import interview.homegrown.modules.drill.web.dto.PlanView;
import interview.homegrown.modules.drill.web.dto.StudyPlanDraft;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/**
 * 入口：学习方向（study_plan）的增与列。
 *
 * <ul>
 *   <li>POST /study-plan/intake —— 无状态多轮对话，返回 {reply, draft}</li>
 *   <li>POST /study-plan/confirm —— 把 draft 落库（clamp layer、同名幂等）</li>
 *   <li>GET  /study-plan        —— 列出我的方向（含知识点与精熟计数）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/study-plan")
public class StudyPlanController {

    private final StudyPlanService service;

    public StudyPlanController(StudyPlanService service) {
        this.service = service;
    }

    @PostMapping("/intake")
    public IntakeResponse intake(@RequestBody IntakeRequest req) {
        return service.intake(req.messages(), req.corpusId());
    }

    @PostMapping("/confirm")
    public PlanView confirm(@RequestBody ConfirmRequest req) {
        return service.confirm(currentUserId(), req.draft());
    }

    @GetMapping
    public List<PlanView> list() {
        return service.list(currentUserId());
    }

    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Long)) {
            throw new ResponseStatusException(UNAUTHORIZED, "未鉴权");
        }
        return (Long) auth.getPrincipal();
    }

    public record IntakeRequest(List<ChatMessage> messages, Long corpusId) {}
    public record ConfirmRequest(StudyPlanDraft draft) {}
}
