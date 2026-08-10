package interview.homegrown.modules.drill.web.dto;

/** intake 对话的一条消息（无状态：前端每轮把完整历史发后端）。 */
public record ChatMessage(String role, String content) {
}
