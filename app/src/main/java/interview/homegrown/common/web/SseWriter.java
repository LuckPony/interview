package interview.homegrown.common.web;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 复用学习模块的 SSE 写入/心跳机制；共享调度线程，断连后中断对应的模型请求。 */
public final class SseWriter implements AutoCloseable {
  private static final ScheduledThreadPoolExecutor HEARTBEATS = heartbeatExecutor();
  private final OutputStream out;
  private final Thread owner = Thread.currentThread();
  private final ScheduledFuture<?> heartbeat;
  private volatile boolean closed;

  private static ScheduledThreadPoolExecutor heartbeatExecutor() {
    ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(2, runnable -> {
      Thread thread = new Thread(runnable, "sse-heartbeat");
      thread.setDaemon(true);
      return thread;
    });
    executor.setRemoveOnCancelPolicy(true);
    return executor;
  }

  public SseWriter(OutputStream out) {
    this.out = out;
    heartbeat = HEARTBEATS.scheduleAtFixedRate(this::sendHeartbeat, 20, 20, TimeUnit.SECONDS);
  }

  private synchronized void sendHeartbeat() {
    if (closed) return;
    try {
      write(": keepalive\n\n");
    } catch (IOException disconnected) {
      close();
      // 与正常 close 同步，避免已完成请求的线程被迟到的心跳错误中断。
      owner.interrupt();
    }
  }

  public synchronized void write(String frame) throws IOException {
    if (closed) throw new IOException("SSE connection closed");
    out.write(frame.getBytes(StandardCharsets.UTF_8));
    out.flush();
  }

  @Override
  public synchronized void close() {
    closed = true;
    heartbeat.cancel(false);
  }
}
