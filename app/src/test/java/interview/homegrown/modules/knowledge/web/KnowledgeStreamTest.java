package interview.homegrown.modules.knowledge.web;

import interview.homegrown.common.ai.LlmRawClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class KnowledgeStreamTest {
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
    var out = new ByteArrayOutputStream();
    controller.ask(new KnowledgeController.AskRequest("分析代码", null, List.of())).getBody().writeTo(out);
    assertThat(out.toString(StandardCharsets.UTF_8)).contains("event: status", "event: done", "return value")
        .doesNotContain("private reasoning", "event: error");
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
    var out = new ByteArrayOutputStream();
    controller.ask(new KnowledgeController.AskRequest("继续", null, List.of())).getBody().writeTo(out);
    assertThat(out.toString(StandardCharsets.UTF_8)).contains("已有内容", "event: error", "已保留")
        .doesNotContain("event: done", "java.lang.InterruptedException");
  }
}
