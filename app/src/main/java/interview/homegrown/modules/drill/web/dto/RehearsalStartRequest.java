package interview.homegrown.modules.drill.web.dto;

/**
 * 开场模拟面试。conceptId 可空 —— 不指定时由服务端挑一个已达 L2 且最久没练的概念，
 * 避免用户永远只挑自己最熟的那个来刷"面试达标"。
 */
public record RehearsalStartRequest(Long conceptId) {
}
