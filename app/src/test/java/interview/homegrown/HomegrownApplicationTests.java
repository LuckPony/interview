package interview.homegrown;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;


@SpringBootTest
class HomegrownApplicationTests {

	@Autowired
	private ApplicationContext context;

	@Test
	@DisplayName("Spring 上下文加载成功")
	void contextLoads() {
		assertThat(context).isNotNull();
	}

	@Test
	@DisplayName("数据源配置正确")
	void dataSourceLoaded(){
		assertThat(context.getBean(DataSource.class)).isNotNull();
	}

}
