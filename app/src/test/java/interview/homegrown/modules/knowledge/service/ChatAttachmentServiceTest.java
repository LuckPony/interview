package interview.homegrown.modules.knowledge.service;

import interview.homegrown.common.exception.BusinessException;
import interview.homegrown.infrastructure.file.DocumentParseService;
import interview.homegrown.infrastructure.file.TextCleaningService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatAttachmentServiceTest {
  private final ChatAttachmentService service = new ChatAttachmentService(new DocumentParseService(new TextCleaningService()));

  @Test
  @DisplayName("代码附件保留缩进和换行，去掉路径且不保存原文件")
  void preservesCode() throws Exception {
    String code = "func f() {\n\tif ok {\n\t\treturn\n\t}\n}\n";
    var result = service.parse(file("C:\\local\\main.go", code));
    assertThat(result.name()).isEqualTo("main.go");
    assertThat(result.text()).isEqualTo(code);
    assertThat(result.characters()).isEqualTo(code.length());
  }

  @Test
  @DisplayName("超长附件明确报错而不是静默截断")
  void rejectsOversizedText() {
    assertThatThrownBy(() -> service.parse(file("long.txt", "x".repeat(30001))))
        .isInstanceOf(BusinessException.class).hasMessageContaining("30000");
  }

  @Test
  @DisplayName("拒绝空文件、伪装文档、不支持的格式和二进制代码")
  void rejectsInvalidFiles() {
    for (var file : new MockMultipartFile[] { file("empty.txt", ""), file("fake.pdf", "plain text"),
        file("archive.zip", "plain text"), file("main.java", "a\u0000b") }) {
      assertThatThrownBy(() -> service.parse(file)).isInstanceOf(BusinessException.class);
    }
  }

  private MockMultipartFile file(String name, String text) {
    return new MockMultipartFile("file", name, "application/octet-stream", text.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  @DisplayName("复用 Tika 解析真实 Word 文档的文字，不创建持久化文件")
  void parsesWordDocument() throws Exception {
    try (var document = new XWPFDocument(); var bytes = new ByteArrayOutputStream()) {
      document.createParagraph().createRun().setText("学习笔记：理解线程池与任务队列");
      document.createParagraph().createRun().setText("    return value;");
      document.write(bytes);
      var result = service.parse(new MockMultipartFile("file", "notes.docx",
          "application/octet-stream", bytes.toByteArray()));
      assertThat(result.text()).contains("理解线程池", "    return value;");
    }
  }
}
