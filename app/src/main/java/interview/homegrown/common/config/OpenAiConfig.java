package interview.homegrown.common.config;


import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI / Swagger UI 全局配置
 * - Swagger UI:   <a href="http://localhost:8080/swagger.html">...</a>
 * - OpenAPI JSON: <a href="http://localhost:8080/v3/api-docs">...</a>
 */
@Configuration
public class OpenAiConfig {

    @Bean
    public OpenAPI interviewOpenApi(){
        return new OpenAPI().info(new Info()
                .title("AI智能面试辅助平台API")
                .description("简历分析/模拟面试/知识库RAG/语音面试等接口文档\n\n")
                .version("0.0.1")
                .contact(new Contact().name("interview-homegrown"))
        );
    }
}
