package interview.homegrown.modules.drill.web.dto;

/** 对话式作答请求：用户在聊天页发送的一条消息。 */
public record ChatRequest(String rawAnswer) {
}
