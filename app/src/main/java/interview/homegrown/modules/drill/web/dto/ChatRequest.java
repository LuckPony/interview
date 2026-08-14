package interview.homegrown.modules.drill.web.dto;

/**
 * 对话式作答请求：用户在聊天页发送的一条消息。
 *
 * @param rawAnswer 用户消息正文
 * @param reveal    用户显式点击「看答案」按钮 → true（服务端据此设置答案揭示边界，
 *                  并让 AI 给出完整答案；不依赖关键词猜测）。null/缺省视为普通消息。
 */
public record ChatRequest(String rawAnswer, Boolean reveal) {
    public ChatRequest {
        reveal = reveal != null && reveal;
    }
}
