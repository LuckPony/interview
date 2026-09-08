package interview.homegrown.modules.knowledge.service;

import interview.homegrown.common.exception.BusinessException;
import interview.homegrown.common.exception.ErrorCode;
import interview.homegrown.infrastructure.file.DocumentParseService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/** 临时解析附件，不保存原文件、不创建知识库资料；仅在用户发送时交给其配置的模型。 */
@Service
public class ChatAttachmentService {
  public static final int MAX_CHARS = 30000;
  public static final long MAX_BYTES = 10 * 1024 * 1024;
  private static final Set<String> DOCUMENTS = Set.of("pdf", "doc", "docx");
  private static final Set<String> TEXT = Set.of("txt", "md", "markdown", "java", "kt", "go", "py", "js", "jsx",
      "ts", "tsx", "json", "yaml", "yml", "xml", "sql", "sh", "bash", "ps1", "c", "cpp", "h", "cs", "rs",
      "vue", "html", "css", "scss", "properties", "log", "csv");
  private final DocumentParseService parser;

  public ChatAttachmentService(DocumentParseService parser) {
    this.parser = parser;
  }

  public record Attachment(String name, String text, int characters) {}

  public Attachment parse(MultipartFile file) throws IOException {
    if (file.isEmpty()) throw new BusinessException(ErrorCode.BAD_REQUEST, "文件内容为空");
    if (file.getSize() > MAX_BYTES) throw new BusinessException(ErrorCode.FILE_TOO_LARGE, "对话附件每个不超过 10 MB");
    String name = file.getOriginalFilename() == null ? "附件.txt" : file.getOriginalFilename();
    name = name.replace('\\', '/');
    name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\r\\n\\p{Cntrl}]", "_");
    if (name.length() > 180) throw new BusinessException(ErrorCode.BAD_REQUEST, "文件名过长");
    String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    if (!DOCUMENTS.contains(extension) && !TEXT.contains(extension)) {
      throw new BusinessException(ErrorCode.FILE_TYPE_NOT_SUPPORTED, "支持 PDF、Word、文本及常用代码文件，暂不支持图片或压缩包");
    }
    byte[] bytes = file.getBytes();
    String text;
    if (DOCUMENTS.contains(extension)) {
      String mime = parser.detectContectType(bytes);
      if (!Set.of("application/pdf", "application/msword", "application/x-tika-ooxml",
          "application/vnd.openxmlformats-officedocument.wordprocessingml.document").contains(mime)) {
        throw new BusinessException(ErrorCode.FILE_TYPE_NOT_SUPPORTED, "文件实际内容与文档类型不匹配");
      }
      text = parser.parseTextPreservingLayout(bytes, name, MAX_CHARS);
    } else {
      try {
        text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
      } catch (CharacterCodingException invalid) {
        throw new BusinessException(ErrorCode.FILE_PARSE_FAILED, "代码或文本请使用 UTF-8 编码，不支持二进制文件");
      }
      if (text.chars().anyMatch(c -> c < 32 && c != '\n' && c != '\r' && c != '\t' && c != '\f')) {
        throw new BusinessException(ErrorCode.FILE_TYPE_NOT_SUPPORTED, "文件包含二进制内容，不能作为文本附件");
      }
      text = text.replaceFirst("^\\uFEFF", "").replace("\r\n", "\n");
    }
    if (text.isBlank()) throw new BusinessException(ErrorCode.FILE_PARSE_FAILED, "未提取到文字，扫描件请先转成带文字层的 PDF");
    if (text.length() > MAX_CHARS) throw new BusinessException(ErrorCode.FILE_TOO_LARGE, "附件文字超过 30000 字符，请拆分后上传");
    return new Attachment(name, text, text.length());
  }
}
