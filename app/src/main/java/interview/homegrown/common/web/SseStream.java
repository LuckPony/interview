package interview.homegrown.common.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * SSE 流式端点统一入口：Spring 官方 {@link SseEmitter} 通道 + 统一帧封装 + 心跳保活。
 *
 * <p>用法：控制器返回 {@code ResponseEntity<SseEmitter>}，端点里调 {@link #start(Handler)}，
 * handler 中通过 {@link Sink} 推帧（token / reasoning / done / event / error）。
 * handler 跑在虚拟线程上、不占 servlet 线程；抛出的异常统一兜底为 {@code event: error} 帧。
 *
 * <p>客户端断开由写失败感知并置 {@link Sink#isBroken()}（调用方据此跳过写库）；
 * 心跳每 20 秒发一帧 {@code : keepalive} 注释保活（防 nginx 等代理 idle 断连），
 * 心跳失败会中断 handler 线程，及时中止上游 LLM 请求。
 *
 * <p>帧格式与前端解析器约定一致（换传输层不换协议）：正文 {@code data:{"text":...}}，
 * 命名事件 {@code event:xxx} + 单行 JSON data，空行分隔。
 */
public final class SseStream {

    private static final Logger log = LoggerFactory.getLogger(SseStream.class);

    /** 每条流一个虚拟线程（配合 spring.threads.virtual.enabled）；阻塞式 LLM 读流适合虚拟线程。 */
    private static final ExecutorService HANDLERS = Executors.newVirtualThreadPerTaskExecutor();

    /** 心跳调度：守护线程共享；handler 结束时 cancel 对应任务。 */
    private static final ScheduledThreadPoolExecutor HEARTBEATS = heartbeatExecutor();

    private SseStream() {
    }

    private static ScheduledThreadPoolExecutor heartbeatExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(2, runnable -> {
            Thread thread = new Thread(runnable, "sse-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    /** 流式处理器：在 sink 上推帧；异常由 {@link #start} 统一兜底为 event:error。 */
    @FunctionalInterface
    public interface Handler {
        void handle(Sink sink) throws Exception;
    }

    /**
     * 开启一条 SSE 流：立即返回 {@code text/event-stream} 响应（含防缓冲头），
     * handler 在虚拟线程上异步执行，结束或异常兜底后 complete。
     * 返回前的 send/complete 会被 emitter 缓冲、attach 后重放，无“先跑后返回”的竞态。
     */
    public static ResponseEntity<SseEmitter> start(Handler handler) {
        SseEmitter emitter = new SseEmitter();
        HANDLERS.execute(() -> {
            Sink sink = new Sink(emitter);
            try {
                handler.handle(sink);
            } catch (Exception e) {
                log.warn("SSE 推送异常", e);
                sink.error(e.getMessage() == null ? "服务器内部错误" : e.getMessage());
            } finally {
                sink.finish();
            }
        });
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(emitter);
    }

    /** SSE 帧写入器：方法即帧类型；写失败置 broken 后静默（isBroken() 供调用方跳过写库）。 */
    public static final class Sink {
        /** handler 执行线程：心跳发现断连时中断它，以中止阻塞中的上游 LLM 读流。 */
        private final Thread owner = Thread.currentThread();
        private final SseEmitter emitter;
        private final ScheduledFuture<?> heartbeat;
        private volatile boolean broken;
        private volatile boolean closed;

        private Sink(SseEmitter emitter) {
            this.emitter = emitter;
            this.emitter.onError(t -> broken = true);
            this.emitter.onTimeout(() -> broken = true);
            this.heartbeat = HEARTBEATS.scheduleAtFixedRate(this::keepalive, 20, 20, TimeUnit.SECONDS);
        }

        /** 客户端是否已断开（写失败/超时/错误后为 true，调用方据此跳过写库）。 */
        public boolean isBroken() {
            return broken;
        }

        /** event:start 空帧：通知前端连接已建立。 */
        public void start() {
            send("start", "{}");
        }

        /** 逐 token 推正文（默认 message 事件：data:{"text":...}）。 */
        public void token(String text) {
            send(null, "{\"text\":\"" + jsonEscape(text) + "\"}");
        }

        /** 逐 token 推思考过程（event:reasoning）。 */
        public void reasoning(String text) {
            send("reasoning", "{\"text\":\"" + jsonEscape(text) + "\"}");
        }

        /** 正常结束（event:done，无附带数据）。 */
        public void done() {
            send("done", "{}");
        }

        /** 正文完整文本随 event:done 下发（前端可整段兜底展示）。 */
        public void doneWithText(String text) {
            send("done", "{\"text\":\"" + jsonEscape(text == null ? "" : text) + "\"}");
        }

        /** 自定义事件帧（grade / result / reveal / draft / status...）；json 为序列化好的单行 JSON。 */
        public void event(String name, String json) {
            send(name, json);
        }

        /** 流式失败：event:error，data:{"message":...}。 */
        public void error(String msg) {
            String message = (msg == null || msg.isBlank()) ? "服务器内部错误" : msg;
            send("error", "{\"message\":\"" + jsonEscape(message) + "\"}");
        }

        private void send(String event, String json) {
            if (broken) return;
            try {
                SseEmitter.SseEventBuilder frame = SseEmitter.event();
                if (event != null) frame.name(event);
                emitter.send(frame.data(json));
            } catch (Exception e) {
                broken = true;
                log.debug("SSE 写入失败（客户端断开）: {}", e.getMessage());
            }
        }

        /** 心跳：保活注释帧；发现断连则置 broken 并中断 handler，中止上游生成。 */
        private void keepalive() {
            if (closed) return;
            if (broken) {
                owner.interrupt();
                return;
            }
            try {
                emitter.send(SseEmitter.event().comment("keepalive"));
            } catch (Exception disconnected) {
                broken = true;
                owner.interrupt();
            }
        }

        /** handler 收尾：停心跳 + complete（连接已断时 complete 抛异常，忽略）。 */
        private void finish() {
            closed = true;
            heartbeat.cancel(false);
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // 客户端已断开或请求已结束
            }
        }

        /** JSON 字符串转义：控制符/引号/反斜杠 + U+2028/2029 —— data 保持单行且是合法 JSON。 */
        private static String jsonEscape(String s) {
            if (s == null) return "";
            StringBuilder b = new StringBuilder(s.length() + 8);
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> b.append("\\\"");
                    case '\\' -> b.append("\\\\");
                    case '\n' -> b.append("\\n");
                    case '\r' -> b.append("\\r");
                    case '\t' -> b.append("\\t");
                    default -> {
                        if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                            b.append(String.format("\\u%04x", (int) c));
                        } else {
                            b.append(c);
                        }
                    }
                }
            }
            return b.toString();
        }
    }
}
