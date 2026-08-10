package interview.homegrown.modules.drill.web.dto;

/**
 * intake 每一轮的结构化返回（复用 StructuredOutputInvoker，强制 JSON schema）。
 * reply 永远是给用户的自然语言；draft 非空时前端展示「确认规划」卡片。
 */
public record IntakeResponse(String reply, StudyPlanDraft draft) {
}
