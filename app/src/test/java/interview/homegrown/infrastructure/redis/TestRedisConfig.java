package interview.homegrown.infrastructure.redis;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.mockito.Mockito.mock;

/**
 * 测试用 Redis 配置：提供 Mock 的 RedisConnectionFactory，
 * 使 {@link SpringBootTest} 集成测试无需真实 Redis 实例即可启动上下文。
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestRedisConfig {

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        return mock(RedisConnectionFactory.class);
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }
}