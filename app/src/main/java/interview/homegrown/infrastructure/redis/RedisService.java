package interview.homegrown.infrastructure.redis;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis 缓存服务（spring-data-redis + Lettuce）。
 *
 * <p>2026-08 由进程内缓存重构为真实 Redis：保留原方法签名，调用方（InterviewSessionService 等）
 * 无需改动。字符串直接存、读 JSON 等场景推荐使用；跨实例部署时运行时可正确共享，
 * 不再存在单机缓存"串台"问题。</p>
 *
 * <p>连接配置见 {@code application.yml} 的 {@code spring.data.redis.*}，由 Spring Boot
 * 自动装配 {@link StringRedisTemplate}（Lettuce 连接工厂）。</p>
 */
@Service
public class RedisService {

    private final StringRedisTemplate redis;

    public RedisService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 设置键值对（带过期时间）。 */
    public void set(String key, String value, Duration timeout) {
        redis.opsForValue().set(key, value, timeout);
    }

    /** 设置键值对（不过期）。 */
    public void set(String key, String value) {
        redis.opsForValue().set(key, value);
    }

    /** 获取值；键不存在或已过期返回空。 */
    public Optional<String> get(String key) {
        return Optional.ofNullable(redis.opsForValue().get(key));
    }

    /** 删除键。 */
    public boolean delete(String key) {
        return Boolean.TRUE.equals(redis.delete(key));
    }

    /** 判断是否存在（未过期）。 */
    public boolean hasKey(String key) {
        return Boolean.TRUE.equals(redis.hasKey(key));
    }

    /** 原子自增（计数场景），返回自增后的值。 */
    public Long increment(String key) {
        Long value = redis.opsForValue().increment(key);
        return value == null ? 1L : value;
    }

    /** 设置过期时间。 */
    public boolean expire(String key, Duration timeout) {
        return Boolean.TRUE.equals(redis.expire(key, timeout));
    }
}