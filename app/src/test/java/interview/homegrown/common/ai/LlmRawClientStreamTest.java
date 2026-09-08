package interview.homegrown.common.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmRawClientStreamTest {
  private LlmRawClient client;
  private HttpClient http;
  private final AtomicReference<Throwable> failure = new AtomicReference<>();

  @BeforeEach
  void setup() {
    var settings = mock(AiSettingsService.class);
    var config = new AiConfig("deepseek", "https://example.invalid/v1", "test-only", "deepseek-chat", 0.7, "low");
    when(settings.currentProvider()).thenReturn(config);
    when(settings.currentProviderForRequest()).thenReturn(config);
    client = new LlmRawClient(settings);
    http = mock(HttpClient.class);
    ReflectionTestUtils.setField(client, "httpClient", http);
  }

  @Test
  @DisplayName("请求线程中断后不重试、不继续消耗模型额度")
  void interruptDoesNotRetry() throws Exception {
    when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
        .thenThrow(new InterruptedException("cancelled"));
    try {
      client.stream("system", "question", token -> {}, failure::set);
      verify(http, times(1)).send(any(HttpRequest.class), any());
      assertThat(failure.get()).isInstanceOf(InterruptedException.class);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  @DisplayName("下游断连异常不被当作解析错误吞掉，并关闭模型响应流")
  void callbackFailureClosesUpstream() throws Exception {
    var body = response("data: {\"choices\":[{\"delta\":{\"content\":\"first\"}}]}\n\n"
        + "data: {\"choices\":[{\"delta\":{\"content\":\"second\"}}]}\n\ndata: [DONE]\n\n");
    var problem = new UncheckedIOException(new IOException("client disconnected"));
    client.stream("system", "question", token -> { throw problem; }, failure::set);
    assertThat(failure.get()).isSameAs(problem);
    assertThat(body.closed).isTrue();
    verify(http, times(1)).send(any(HttpRequest.class), any());
  }

  @Test
  @DisplayName("长代码的缩进换行保留，正常 DONE 完成并关闭流")
  void preservesCode() throws Exception {
    var body = response("data: {\"choices\":[{\"delta\":{\"content\":\"\\treturn x\"}}]}\n\n"
        + "data: {\"choices\":[{\"delta\":{\"content\":\"\\n\"}}]}\n\ndata: [DONE]\n\n");
    var answer = new StringBuilder();
    client.stream("system", "question", answer::append, failure::set);
    assertThat(answer.toString()).isEqualTo("\treturn x\n");
    assertThat(failure.get()).isNull();
    assertThat(body.closed).isTrue();
  }

  @Test
  @DisplayName("上游提前断流不能把部分回答误判为成功")
  void prematureEof() throws Exception {
    response("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n");
    var answer = new StringBuilder();
    client.stream("system", "question", answer::append, failure::set);
    assertThat(answer.toString()).isEqualTo("partial");
    assertThat(failure.get()).isInstanceOf(EOFException.class);
  }

  @SuppressWarnings("unchecked")
  private TrackedStream response(String frames) throws Exception {
    HttpResponse<InputStream> response = mock(HttpResponse.class);
    var stream = new TrackedStream(frames);
    when(response.statusCode()).thenReturn(200);
    when(response.body()).thenReturn(stream);
    when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
        .thenReturn(response);
    return stream;
  }

  private static final class TrackedStream extends ByteArrayInputStream {
    private boolean closed;
    TrackedStream(String text) { super(text.getBytes(StandardCharsets.UTF_8)); }
    @Override
    public void close() throws IOException { closed = true; super.close(); }
  }
}
