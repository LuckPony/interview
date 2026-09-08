package interview.homegrown.common.ai;

import interview.homegrown.common.config.AiConfigProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.mockito.Mockito.mock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiTaskConfigTest {
  @Test @DisplayName("异步资料索引使用发起用户配置，异常退出后不泄漏到后续用户")
  void isolatedSnapshot() {
    AiSettingsService settings = new AiSettingsService(mock(JdbcTemplate.class), new AiConfigProperties());
    var first = new AiConfig("first", "http://example.invalid", "test-one", "test", 0.2, null);
    var second = new AiConfig("second", "http://example.invalid", "test-two", "test", 0.2, null);
    settings.withTaskConfig(first, () -> {
      assertThat(settings.currentProviderForRequest()).isSameAs(first);
      assertThatThrownBy(() -> settings.withTaskConfig(second, () -> {
        assertThat(settings.currentProviderForRequest()).isSameAs(second);
        throw new IllegalStateException("测试退出");
      })).isInstanceOf(IllegalStateException.class);
      assertThat(settings.currentProviderForRequest()).isSameAs(first);
    });
    assertThat(settings.currentProviderForRequest().apiKey()).isBlank();
  }
}
