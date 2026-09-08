package interview.homegrown.modules.drill.web;

import interview.homegrown.common.result.Result;
import interview.homegrown.common.ai.AiSettingsService;
import interview.homegrown.common.exception.BusinessException;
import interview.homegrown.common.exception.ErrorCode;
import interview.homegrown.modules.drill.service.CorpusLibraryService;
import interview.homegrown.modules.drill.service.CorpusIndexer;
import interview.homegrown.modules.drill.web.dto.CorpusDetail;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@RestController
@RequestMapping("/api/corpus")
public class CorpusLibraryController {
  private final CorpusLibraryService library;
  private final CorpusIndexer indexer;
  private final AiSettingsService settings;

  public CorpusLibraryController(CorpusLibraryService library, CorpusIndexer indexer, AiSettingsService settings) {
    this.library = library; this.indexer = indexer; this.settings = settings;
  }

  @GetMapping("/{id}")
  public Result<CorpusDetail> detail(@PathVariable Long id) { return Result.success(library.detail(id, userId())); }

  @GetMapping("/{id}/text")
  public Result<CorpusLibraryService.TextView> text(@PathVariable Long id, @RequestParam(required = false) Long sectionId) {
    return Result.success(library.text(id, userId(), sectionId));
  }

  @PostMapping("/{id}/reindex")
  public Result<Void> reindex(@PathVariable Long id) {
    library.requireOwned(id, userId());
    indexer.indexAsync(id, true);
    return Result.success();
  }

  public record OriginalLink(String path) {}
  @PostMapping("/{id}/original-link")
  public Result<OriginalLink> originalLink(@PathVariable Long id) {
    return Result.success(new OriginalLink(library.originalTicket(id, userId())));
  }

  @PostMapping("/{id}/parsed-link")
  public Result<OriginalLink> parsedLink(@PathVariable Long id) {
    return Result.success(new OriginalLink(library.textTicket(id, userId())));
  }

  /** 外部浏览器没有 SPA 登录头，只接受五分钟有效的专用随机凭证，不暴露登录 token。 */
  @GetMapping("/original/{ticket}")
  public ResponseEntity<?> original(@PathVariable String ticket, @RequestParam(defaultValue = "false") boolean download) {
    var document = library.fromTicket(ticket);
    var response = ResponseEntity.ok().header("Cache-Control", "private, no-store")
        .header("Referrer-Policy", "no-referrer").header("X-Content-Type-Options", "nosniff");
    if (document.getOriginalKey() == null || document.getOriginalKey().isBlank()) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "未保存原文件，请重新上传原件或查看解析文本");
    }
    String name = document.getName().toLowerCase(Locale.ROOT);
    boolean pdf = "application/pdf".equals(document.getOriginalType());
    boolean plain = name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".markdown") || name.endsWith(".mdx");
    // Word 等格式浏览器不能原生显示，下载原件；不发送给第三方 Office 预览服务。
    boolean inline = !download && (pdf || plain);
    MediaType type = pdf ? MediaType.APPLICATION_PDF : plain ? MediaType.TEXT_PLAIN : MediaType.APPLICATION_OCTET_STREAM;
    return response.header("Content-Security-Policy", "frame-ancestors 'none'; base-uri 'none'")
        .header("Content-Disposition", (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
            .filename(document.getName(), StandardCharsets.UTF_8).build().toString())
        .contentType(type).body(library.original(document));
  }

  @GetMapping("/parsed/{ticket}")
  public ResponseEntity<String> parsed(@PathVariable String ticket) {
    var document = library.fromTicket(ticket);
    var response = ResponseEntity.ok().header("Cache-Control", "private, no-store")
        .header("Referrer-Policy", "no-referrer").header("X-Content-Type-Options", "nosniff");
    String name = HtmlUtils.htmlEscape(document.getName());
    String original = "这是用于索引的解析文本，不是原文件，排版、图片与公式可能与原件不同。";
    String html = "<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
        + "<title>" + name + "</title><style>body{max-width:960px;margin:48px auto;padding:0 24px;background:#f5f9f7;color:#263d36;font:16px/1.8 system-ui}pre{white-space:pre-wrap;overflow-wrap:anywhere;padding:28px;background:white;border:1px solid #dce6e0;border-radius:16px;font:14px/1.8 ui-monospace,monospace}a{color:#37796d}small{color:#687b74}</style>"
        + "<h1>" + name + "</h1><small>" + original + "</small><pre>" + HtmlUtils.htmlEscape(document.getText()) + "</pre></html>";
    return response.header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'")
        .contentType(new MediaType("text", "html", StandardCharsets.UTF_8)).body(html);
  }

  private Long userId() {
    Long id = settings.currentUserId();
    if (id == null) throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
    return id;
  }
}
