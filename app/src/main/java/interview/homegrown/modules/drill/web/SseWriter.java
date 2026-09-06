package interview.homegrown.modules.drill.web;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 线程安全的 SSE 写出器：
 * <p>
 * 所有写操作都在同一把锁上同步，避免与后台心跳线程交错写出而破坏 SSE 帧（{"text":"..."}）。
 * 后台心跳固定回写一行注释帧 {@code : keepalive\\n\\n} 到 nginx，防止模型在思考期……
 * 不流式泄露 reasoning（或首字节前等待）时出现长时间静默，从而被 {@code proxy_read_timeout} 掐断成 502。
 * </p>
 * 心跳间隔固定 20s，远小于 nginx 的读超时（已放宽至 3600s），且对客户端是透明注释帧（被忽略）。
 */
final class SseWriter implements AutoCloseable {

    private static final long HEARTBEAT_INTERVAL_SECONDS = 20;

    private final OutputStream out;
    private final Object lock = new Object();
    private final ScheduledExecutorService heart;
    private volatile boolean closed;

    SseWriter(OutputStream out) {
        this.out = out;
        this.heart = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sse-heartbeat");
            t.setDaemon(true);
            return t;
        });
        this.heart.scheduleAtFixedRate(() -> {
            if (closed) return;
            try {
                synchronized (lock) {
                    out.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            } catch (Throwable ignored) {
                // 连接已断等：静默，交由业务写入侧感知。
            }
        }, HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /** 写一段已拼好的 SSE 帧（含 data/event 前缀与结尾空行），自动同步并 flush。 */
    void write(String sse) throws IOException {
        synchronized (lock) {
            if (closed) throw new IOException("SSE 已关闭");
            out.write(sse.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        heart.shutdownNow();
    }
}
