package interview.homegrown.modules.interview.web;

import interview.homegrown.common.result.Result;
import interview.homegrown.modules.interview.model.CreateSessionRequest;
import interview.homegrown.modules.interview.model.InterviewListItemDTO;
import interview.homegrown.modules.interview.model.InterviewSessionDTO;
import interview.homegrown.modules.interview.service.InterviewSessionService;
import interview.homegrown.modules.interview.service.InterviewSkillService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

//模拟面试接口
@RestController
@RequestMapping("/api/interviews")
@Tag(name = "模拟面试", description = "Skill出题、会话管理、逐题问答、评估")
class InterviewController {

    private final InterviewSessionService sessionService;
    private final InterviewSkillService skillService;

    public InterviewController(InterviewSessionService sessionService, InterviewSkillService skillService) {
        this.sessionService = sessionService;
        this.skillService = skillService;
    }

    private Long uid() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (Long) auth.getPrincipal();
    }

    @GetMapping("/skills")
    @Operation(summary = "面试方向列表",description = "返回所有可用的 Skill 方向")
    public Result<Map<String,String>> skills(){
        return Result.success(skillService.listSkills());
    }

    @PostMapping("/sessions")
    @Operation(summary = "创建面试会话", description = "指定方向/难度/题数，LLM生成题目并开始面试")
    public Result<InterviewSessionDTO> createSession(@RequestBody CreateSessionRequest request){
        return Result.success(sessionService.createSession(request, uid()));
    }

    @GetMapping("/sessions/{sessionId}/current-question")
    @Operation(summary = "获取当前题目", description = "按会话进度返回当前需要回答的题目")
    public Result<InterviewSessionService.CurrentQuestion> currentQuestion(@PathVariable String sessionId){
        return Result.success(sessionService.getCurrentQuestion(sessionId, uid()));
    }

    @GetMapping("/sessions/{sessionId}/answers")
    @Operation(summary = "提交答案", description = "提交当前题目的答案并推进到下一题")
    public Result<Void> submitAnswer(@PathVariable String sessionId,
                                     @RequestParam int questionIndex,
                                     @RequestParam String answerText){
        sessionService.submitAnswer(sessionId, questionIndex, answerText, uid());
        return Result.success();
    }

    @PostMapping("/sessions/{sessionId}/complete-evaluate")
    @Operation(summary = "完成面试",description = "触发统一评估，生成总分与逐题评价")
    public Result<InterviewSessionDTO> completeAndEvaluate(@PathVariable String sessionId){
        return Result.success(sessionService.completeInterview(sessionId, uid()));
    }

    @GetMapping("/sessions")
    @Operation(summary = "面试历史列表",description = "返回所有面试会话")
    public Result<List<InterviewListItemDTO>> listSession(){
        return Result.success(sessionService.listSessions(uid()));
    }

    @GetMapping("/session/{sessionId}")
    @Operation(summary = "面试详情", description = "返回会话信息、全部问答与评估结果")
    public Result<InterviewSessionDTO> getSession(@PathVariable String sessionId){
        return Result.success(sessionService.getSession(sessionId, uid()));
    }

}
