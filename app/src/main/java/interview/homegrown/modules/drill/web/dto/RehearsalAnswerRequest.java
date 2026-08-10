package interview.homegrown.modules.drill.web.dto;

/**
 * 模拟面试单轮作答。没有 timing 字段：REHEARSAL 恒为闭卷 + 计时，
 * 这是模式的定义而不是用户选项，所以不接受前端传。
 */
public record RehearsalAnswerRequest(String rawAnswer) {
}
