package interview.homegrown.infrastructure.redis;


import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis 操作服务
 * 封装 StringRedisTemplate，提供常用的缓存操作
 *
 * 职责：
 * - 面试会话状态的临时缓存（替代 ConcurrentHashMap）
 * - 未来扩展：Redis Stream 消息队列、分布式限流计数器
 */
@Service
public class RedisService {
    private final StringRedisTemplate redisTemplate;

    public RedisService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

     // 设置键值对（带过期时间）
    public void set(String key, String value, Duration timeout) {
        redisTemplate.opsForValue().set(key, value, timeout);
    }

    //设置键值对（不过期，仅用于计数器等场景）
    public void set(String key, String value){
        redisTemplate.opsForValue().set(key, value);
    }

    //获取值
    public Optional<String> get(String key) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(key));
    }

    //删除键
    public boolean delete(String key) {
        return Boolean.TRUE.equals(redisTemplate.delete(key));
    }

    //判断是否存在
    public boolean hasKey(String key){
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    //自增
    public Long increment(String key){
        return redisTemplate.opsForValue().increment(key);
    }

    //设置过期时间
    public boolean expire(String key, Duration timeout){
        return Boolean.TRUE.equals(redisTemplate.expire(key, timeout));
    }
}
