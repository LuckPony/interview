package interview.homegrown.common.ai;

import interview.homegrown.common.config.AiConfigProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiSettingsServiceTest {

    @Test
    @DisplayName("保存用户 AI 设置使用 PostgreSQL upsert")
    void updateUsesPostgresUpsert() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(
                eq("SELECT settings_json FROM user_ai_setting WHERE user_id = ?"),
                org.mockito.ArgumentMatchers.<RowMapper<String>>any(),
                eq(1L)))
                .thenReturn(List.of());

        AiConfigProperties startup = new AiConfigProperties();
        AiSettingsService service = new AiSettingsService(jdbc, startup);
        service.update(1L, new AiConfig("deepseek", "https://api.deepseek.com", "sk-test", "deepseek-chat", 0.7, null));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), eq(1L), anyString());
        assertThat(sql.getValue())
                .contains("INSERT INTO user_ai_setting")
                .contains("ON CONFLICT (user_id) DO UPDATE")
                .doesNotContain("MERGE")
                .doesNotContain("KEY(user_id)");
    }
}
