package interview.homegrown.modules.drill.web;

import interview.homegrown.common.ai.AiSettingsService;
import interview.homegrown.common.exception.GlobalExceptionHandler;
import interview.homegrown.modules.drill.domain.Corpus;
import interview.homegrown.modules.drill.service.CorpusIndexer;
import interview.homegrown.modules.drill.service.CorpusLibraryService;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

class CorpusLibraryControllerTest {
  final CorpusLibraryService library = mock(CorpusLibraryService.class);
  final AiSettingsService settings = mock(AiSettingsService.class);
  final Corpus document = new Corpus();
  final byte[] bytes = "%PDF-1.7\nactual-original".getBytes(StandardCharsets.UTF_8);
  MockMvc mvc;

  @BeforeEach void setup() {
    document.setName("学习.pdf"); document.setOriginalKey("original.pdf"); document.setOriginalType("application/pdf");
    document.setText("<script>alert('xss')</script>解析结果");
    when(library.fromTicket("ticket")).thenReturn(document);
    when(library.original(document)).thenReturn(new ByteArrayResource(bytes));
    mvc = MockMvcBuilders.standaloneSetup(new CorpusLibraryController(library, mock(CorpusIndexer.class), settings))
        .setControllerAdvice(new GlobalExceptionHandler()).build();
  }

  @Test @DisplayName("详情 GET 路由存在并调用按当前用户隔离的服务")
  void detailRoute() throws Exception {
    when(settings.currentUserId()).thenReturn(7L);
    var response = mvc.perform(get("/api/corpus/4")).andReturn().getResponse();
    assertThat(response.getStatus()).isEqualTo(200);
    verify(library).detail(4L, 7L);
  }

  @Test @DisplayName("PDF 响应为原始字节并支持浏览器 Range 分段读取")
  void originalPdf() throws Exception {
    var response = mvc.perform(get("/api/corpus/original/ticket")).andReturn().getResponse();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentType()).isEqualTo("application/pdf");
    assertThat(response.getHeader("Content-Disposition")).startsWith("inline;");
    assertThat(response.getHeader("Cache-Control")).contains("no-store");
    assertThat(response.getContentAsByteArray()).isEqualTo(bytes);
    var range = mvc.perform(get("/api/corpus/original/ticket").header("Range", "bytes=0-3")).andReturn().getResponse();
    assertThat(range.getStatus()).isEqualTo(206);
    assertThat(range.getContentAsByteArray()).isEqualTo("%PDF".getBytes(StandardCharsets.UTF_8));
  }

  @Test @DisplayName("Word 提供真实附件，解析文本单独标明且转义 HTML")
  void wordAndParsedText() throws Exception {
    document.setName("面试.docx"); document.setOriginalType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    var word = mvc.perform(get("/api/corpus/original/ticket")).andReturn().getResponse();
    assertThat(word.getHeader("Content-Disposition")).startsWith("attachment;");
    assertThat(word.getContentAsByteArray()).isEqualTo(bytes);
    var parsed = mvc.perform(get("/api/corpus/parsed/ticket")).andReturn().getResponse();
    assertThat(parsed.getContentAsString(StandardCharsets.UTF_8)).contains("不是原文件", "&lt;script&gt;").doesNotContain("<script>");
  }

  @Test @DisplayName("不支持的 HTTP 方法返回 405 而非误报内部服务器错误")
  void unsupportedMethod() throws Exception {
    var response = mvc.perform(put("/api/corpus/4")).andReturn().getResponse();
    assertThat(response.getStatus()).isEqualTo(405);
    assertThat(response.getHeader("Allow")).contains("GET");
    assertThat(response.getContentAsString(StandardCharsets.UTF_8)).contains("前后端已同步更新");
  }
}
