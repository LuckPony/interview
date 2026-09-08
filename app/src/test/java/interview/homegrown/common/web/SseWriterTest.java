package interview.homegrown.common.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class SseWriterTest {
  @Test
  @DisplayName("心跳发送 SSE 注释，正常关闭后的迟到心跳不会误中断线程")
  void heartbeatAndClose() {
    var out = new ByteArrayOutputStream();
    var writer = new SseWriter(out);
    ReflectionTestUtils.invokeMethod(writer, "sendHeartbeat");
    writer.close();
    ReflectionTestUtils.invokeMethod(writer, "sendHeartbeat");
    assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo(": keepalive\n\n");
    assertThat(Thread.currentThread().isInterrupted()).isFalse();
  }

  @Test
  @DisplayName("真实写入失败会中断所属模型请求，释放等待中的上游连接")
  void disconnectedHeartbeatInterruptsOwner() {
    try (var writer = new SseWriter(new OutputStream() {
      @Override public void write(int b) throws IOException { throw new IOException("disconnected"); }
    })) {
      ReflectionTestUtils.invokeMethod(writer, "sendHeartbeat");
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }
}
