package interview.homegrown.common.ai;

import interview.homegrown.common.config.AiConfigProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 云端部署回归锁：后端不配服务器级 key（registry 为空）是常态，
 * 结构化输出（出题/判分/复盘/计划草稿）必须落到用户自己的 key（LlmRawClient）上，
 * 而不是在 registry 查找阶段就抛「没有可用的 AI Provider」。
 */
class StructuredOutputInvokerTest {

    /** 与真实输出 DTO 同构：字段 + getter/setter，供 Jackson 反序列化。 */
    public static class DemoOutput {
        private String reply;

        public String getReply() { return reply; }
        public void setReply(String reply) { this.reply = reply; }
    }

    private AiConfigProperties configWithOneAttempt() {
        AiConfigProperties cfg = new AiConfigProperties();
        cfg.getStructured().setMaxAttempts(1);
        return cfg;
    }

    @Test
    @DisplayName("registry 为空但用户 key 可用：改走 LlmRawClient，不抛 Provider 错误")
    void fallsBackToRawClientWhenRegistryEmpty() {
        LlmProviderRegistry registry = mock(LlmProviderRegistry.class);
        when(registry.getChatClientOrDefault(anyString()))
                .thenThrow(new IllegalStateException("没有可用的 AI Provider，请检查 API Key 配置"));

        LlmRawClient raw = mock(LlmRawClient.class);
        when(raw.availableForCurrentRequest()).thenReturn(true);
        when(raw.complete(anyString(), anyString())).thenReturn("{\"reply\":\"好的，这就帮你规划\"}");

        StructuredOutputInvoker invoker = new StructuredOutputInvoker(registry, configWithOneAttempt(), raw);
        DemoOutput out = invoker.invoke("system", "user", DemoOutput.class);

        assertThat(out).isNotNull();
        assertThat(out.getReply()).isEqualTo("好的，这就帮你规划");
        verify(raw).complete(anyString(), anyString());
    }

    @Test
    @DisplayName("registry 为空且用户也未配 key：给出明确错误而不是模糊的「LLM 返回为空」")
    void throwsClearErrorWhenBothUnavailable() {
        LlmProviderRegistry registry = mock(LlmProviderRegistry.class);
        when(registry.getChatClientOrDefault(anyString()))
                .thenThrow(new IllegalStateException("没有可用的 AI Provider，请检查 API Key 配置"));

        LlmRawClient raw = mock(LlmRawClient.class);
        when(raw.availableForCurrentRequest()).thenReturn(false);

        StructuredOutputInvoker invoker = new StructuredOutputInvoker(registry, configWithOneAttempt(), raw);
        assertThatThrownBy(() -> invoker.invoke("system", "user", DemoOutput.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("尚未配置 API Key");
    }
}
