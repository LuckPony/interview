package interview.homegrown.infrastructure.redis;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内缓存服务（替代 Redis，单机自包含）。
 *
 * <p>保留原 RedisService 的方法签名，调用方（InterviewSessionService 等）无需改动；
 * 实现改为 ConcurrentHashMap + 过期时间戳。单机/桌面场景下会话题目缓存的语义与
 * 原 Redis 24h TTL 等价（应用重启即失效，与单次面试会话的生命周期一致）。</p>
 */
@Service
public class RedisService {

    private static final long NO_EXPIRE = Long.MAX_VALUE;

    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    private record Entry(String value, long expireAtMillis) {
    }

    /** 设置键值对（带过期时间）。 */
    public void set(String key, String value, Duration timeout) {
        store.put(key, new Entry(value, System.currentTimeMillis() + timeout.toMillis()));
    }

    /** 设置键值对（不过期）。 */
    public void set(String key, String value) {
        store.put(key, new Entry(value, NO_EXPIRE));
    }

    /** 获取值；已过期返回空并清理。 */
    public Optional<String> get(String key) {
        Entry e = store.get(key);
        if (e == null) {
            return Optional.empty();
        }
        if (e.expireAtMillis() < System.currentTimeMillis()) {
            store.remove(key);
            return Optional.empty();
        }
        return Optional.ofNullable(e.value());
    }

    /** 删除键。 */
    public boolean delete(String key) {
        return store.remove(key) != null;
    }

    /** 判断是否存在（未过期）。 */
    public boolean hasKey(String key) {
        return get(key).isPresent();
    }

    /** 原子自增（计数场景）。 */
    public Long increment(String key) {
        Entry e = store.compute(key, (k, old) -> {
            long base = 0L;
            if (old != null && old.expireAtMillis() >= System.currentTimeMillis()) {
                try {
                    base = Long.parseLong(old.value());
                } catch (NumberFormatException ignored) {
                    // 非数字值视为 0
                }
            }
            return new Entry(String.valueOf(base + 1), NO_EXPIRE);
        });
        return Long.parseLong(e.value());
    }

    /** 设置过期时间。 */
    public boolean expire(String key, Duration timeout) {
        Entry e = store.get(key);
        if (e == null) {
            return false;
        }
        store.put(key, new Entry(e.value(), System.currentTimeMillis() + timeout.toMillis()));
        return true;
    }
}
