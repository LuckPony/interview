package interview.homegrown.modules.knowledge.web;

import interview.homegrown.common.ai.LlmRawClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class KnowledgeStreamTest {
  @Test @DisplayName("文献翻译使用阅读专用提示，不被普通问答格式强制改成总结")
  void libraryPrompt() throws Exception {
    LlmRawClient client = mock(LlmRawClient.class);
    doAnswer(call -> {
      assertThat(call.<String>getArgument(0)).contains("文献阅读助手", "不得用摘要代替").doesNotContain("补一个具体例子");
      call.<Consumer<String>>getArgument(2).accept("译文");
      return null;
    }).when(client).stream(anyString(), anyString(), any(), any(), eq(false), any());
    var controller = new KnowledgeController(null, null, client, null);
    var frames = awaitFrames(controller.ask(
        new KnowledgeController.AskRequest("翻译这一段", null, List.of(), "library")).getBody());
    assertThat(frames).contains("译文").containsPattern("event:\\s*done");
  }

  @Test
  @DisplayName("SSE 推送处理状态和完整代码正文，推理不混入答案")
  void emitsStatusAndCode() throws Exception {
    LlmRawClient client = mock(LlmRawClient.class);
    doAnswer(call -> {
      call.<Consumer<String>>getArgument(5).accept("private reasoning");
      call.<Consumer<String>>getArgument(2).accept("```go\n\treturn value\n```\n");
      return null;
    }).when(client).stream(anyString(), anyString(), any(), any(), eq(false), any());
    var controller = new KnowledgeController(null, null, client, null);
    var frames = awaitFrames(controller.ask(
        new KnowledgeController.AskRequest("分析代码", null, List.of())).getBody());
    assertThat(frames).containsPattern("event:\\s*status").contains("return value")
        .doesNotContain("private reasoning").doesNotContainPattern("event:\\s*error");
  }

  @Test
  @DisplayName("线程中断发送可理解错误，保留之前的 token，不伪装生成成功")
  void interruptedStream() throws Exception {
    LlmRawClient client = mock(LlmRawClient.class);
    doAnswer(call -> {
      call.<Consumer<String>>getArgument(2).accept("已有内容");
      call.<Consumer<Throwable>>getArgument(3).accept(new InterruptedException());
      return null;
    }).when(client).stream(anyString(), anyString(), any(), any(), eq(false), any());
    var controller = new KnowledgeController(null, null, client, null);
    var frames = awaitFrames(controller.ask(
        new KnowledgeController.AskRequest("继续", null, List.of())).getBody());
    assertThat(frames).contains("已有内容").containsPattern("event:\\s*error").contains("已保留")
        .doesNotContainPattern("event:\\s*done").doesNotContain("java.lang.InterruptedException");
  }

  /**
   * 控制器返回后 handler 在虚拟线程上异步执行；测试不起 async 分发，emitter 始终未 attach，
   * send 出的帧全部缓冲在 ResponseBodyEmitter.earlySendAttempts。轮询把缓冲帧按序拼成 SSE
   * 文本，见到 event:done / event:error 即结束（最多等 5 秒，防 handler 异常时死等）。
   */
  private static String awaitFrames(SseEmitter emitter) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 5000;
    while (true) {
      String text = bufferedText(emitter);
      if (text.matches("(?s).*event:\\s*(done|error).*") || System.currentTimeMillis() > deadline) {
        return text;
      }
      Thread.sleep(10);
    }
  }

  private static String bufferedText(SseEmitter emitter) {
    Set<?> attempts = (Set<?>) ReflectionTestUtils.getField(emitter, "earlySendAttempts");
    if (attempts == null) return "";
    StringBuilder text = new StringBuilder();
    for (Object item : attempts) {
      Object data = ReflectionTestUtils.invokeMethod(item, "getData");
      if (data != null) text.append(data);
    }
    return text.toString();
  }
}
