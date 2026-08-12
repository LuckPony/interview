package interview.homegrown.common.ai;

/** 用户可配置的 AI Provider 设置（运行时，持久化到 ai_setting 表）。 */
public record AiConfig(String provider, String baseUrl, String apiKey, String model, double temperature) {
}
