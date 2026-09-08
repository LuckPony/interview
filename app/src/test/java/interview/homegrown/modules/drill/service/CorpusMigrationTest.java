package interview.homegrown.modules.drill.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.assertj.core.api.Assertions.assertThat;

class CorpusMigrationTest {
  @Test @DisplayName("V46 迁移保留历史资料并给已有章节设置基础索引状态")
  void migratesLegacyCorpus() {
    var source = new DriverManagerDataSource("jdbc:h2:mem:corpusmigration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
    var jdbc = new JdbcTemplate(source);
    jdbc.execute("CREATE TABLE corpus(id BIGINT PRIMARY KEY, text TEXT)");
    jdbc.execute("CREATE TABLE corpus_chunk(corpus_id BIGINT)");
    jdbc.execute("CREATE TABLE interview_session(id VARCHAR(36) PRIMARY KEY)");
    jdbc.update("INSERT INTO corpus VALUES(1, '历史原文'), (2, '尚未索引')");
    jdbc.update("INSERT INTO corpus_chunk VALUES(1)");
    new ResourceDatabasePopulator(new ClassPathResource("db/migration/common/V46__corpus_library.sql")).execute(source);
    assertThat(jdbc.queryForObject("SELECT index_state FROM corpus WHERE id=1", String.class)).isEqualTo("BASIC");
    assertThat(jdbc.queryForObject("SELECT index_state FROM corpus WHERE id=2", String.class)).isEqualTo("PENDING");
    assertThat(jdbc.queryForObject("SELECT text FROM corpus WHERE id=1", String.class)).isEqualTo("历史原文");
    jdbc.execute("DROP ALL OBJECTS");
  }
}
