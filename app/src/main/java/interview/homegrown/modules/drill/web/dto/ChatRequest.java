package interview.homegrown.modules.drill.web.dto;

import java.util.List;

/**
 * 对话式作答请求：用户在聊天页发送的一条消息。
 *
 * @param rawAnswer 用户消息正文
 * @param reveal    用户显式点击「看答案」按钮 → true（服务端据此设置答案揭示边界，
 *                  并让 AI 给出完整答案；不依赖关键词猜测）。null/缺省视为普通消息。
 * @param images    消息附带的图片（data URL，如 {@code data:image/png;base64,...}）；
 *                  仅当前模型支持视觉时允许，否则后端 400 明确提示。
 */
public record ChatRequest(String rawAnswer, Boolean reveal, List<String> images) {
    public ChatRequest {
        reveal = reveal != null && reveal;
        images = images == null ? List.of() : images;
    }

    public ChatRequest(String rawAnswer, Boolean reveal) {
        this(rawAnswer, reveal, List.of());
    }
}
