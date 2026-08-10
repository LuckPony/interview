package interview.homegrown.modules.drill.web.dto;

/** 提交作答请求。timing/activeSeconds 为 opt-in 计时相关，可空。 */
public record SubmitRequest(String rawAnswer, String timing, Integer activeSeconds) {
}
