package interview.homegrown.modules.knowledge.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeAsyncTimeoutTest {
  @Test
  @DisplayName("应用 YAML 中的超时实际绑定到 MVC 异步处理器，不再使用容器默认短超时")
  void mvcTimeoutIsApplied() throws Exception {
    var sources = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
    new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(WebMvcAutoConfiguration.class))
        .withInitializer(context -> sources.forEach(source -> context.getEnvironment().getPropertySources().addLast(source)))
        .run(context -> {
          assertThat(context).hasNotFailed();
          var adapter = context.getBean(RequestMappingHandlerAdapter.class);
          assertThat(ReflectionTestUtils.getField(adapter, "asyncRequestTimeout")).isEqualTo(3600000L);
        });
  }
}
