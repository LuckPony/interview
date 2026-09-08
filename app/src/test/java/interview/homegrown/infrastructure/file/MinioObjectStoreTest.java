package interview.homegrown.infrastructure.file;

import com.sun.net.httpserver.HttpServer;
import interview.homegrown.common.config.StorageConfig;
import interview.homegrown.common.config.StorageProperties;
import interview.homegrown.common.exception.BusinessException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.s3.S3Client;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 使用回环 HTTP 服务验证真实 SDK 的 S3 请求，不接触真实 MinIO 或云端凭据。 */
class MinioObjectStoreTest {
  @TempDir Path directory;
  final AtomicReference<byte[]> object = new AtomicReference<>();
  final AtomicReference<String> requestPath = new AtomicReference<>();
  HttpServer server;
  S3Client client;
  FileStorageService storage;

  @BeforeEach void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      try (exchange) {
        requestPath.set(exchange.getRequestURI().getPath());
        String method = exchange.getRequestMethod();
        if ("PUT".equals(method)) {
          object.set(exchange.getRequestBody().readAllBytes());
          exchange.sendResponseHeaders(200, -1);
        } else if ("DELETE".equals(method)) {
          object.set(null);
          exchange.sendResponseHeaders(204, -1);
        } else if (object.get() == null) {
          exchange.sendResponseHeaders(404, -1);
        } else if ("HEAD".equals(method)) {
          exchange.getResponseHeaders().set("Content-Length", String.valueOf(object.get().length));
          exchange.sendResponseHeaders(200, -1);
        } else {
          exchange.sendResponseHeaders(200, object.get().length);
          exchange.getResponseBody().write(object.get());
        }
      }
    });
    server.start();
    StorageProperties config = new StorageProperties();
    config.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort());
    config.setAccessKey("test-only-key"); config.setSecretKey("test-only-secret");
    config.setBucket("test-bucket");
    client = new StorageConfig().originalFileS3Client(config);
    storage = new FileStorageService(directory.toString(), new MinioObjectStore(client, config));
  }

  @AfterEach void stop() {
    if (client != null) client.close();
    if (server != null) server.stop(0);
  }

  @Test @DisplayName("MinIO 使用私有桶路径式请求，上传下载原始字节不变并可清理")
  void rawFileRoundTrip() throws Exception {
    byte[] bytes = "%PDF-1.7\n原件\u0000\u0001".getBytes(StandardCharsets.UTF_8);
    String key = storage.upload(bytes, "论文.pdf", "application/pdf");
    assertThat(key).matches("minio:test-bucket/[a-f0-9-]{36}\\.pdf");
    assertThat(requestPath.get()).isEqualTo("/test-bucket/" + key.substring(key.lastIndexOf('/') + 1));
    assertThat(object.get()).isEqualTo(bytes);
    var resource = storage.read(key);
    assertThat(resource.contentLength()).isEqualTo(bytes.length);
    assertThat(resource.getContentAsByteArray()).isEqualTo(bytes);
    try (var entries = Files.list(directory)) { assertThat(entries.count()).isZero(); }
    assertThatThrownBy(() -> storage.getUrl(key)).isInstanceOf(BusinessException.class);
    storage.delete(key);
    assertThat(object.get()).isNull();
    assertThatThrownBy(() -> storage.read(key)).isInstanceOf(BusinessException.class).hasMessageContaining("不存在");
  }

  @Test @DisplayName("启用 MinIO 后旧磁盘文件仍可读取，拒绝跨桶及非法原件路径")
  void legacyAndInvalidReferences() throws Exception {
    FileStorageService local = new FileStorageService(directory.toString());
    byte[] bytes = "旧资料".getBytes(StandardCharsets.UTF_8);
    String key = local.upload(bytes, "old.md", "text/markdown");
    assertThat(storage.read(key).getContentAsByteArray()).isEqualTo(bytes);
    assertThatThrownBy(() -> storage.read("minio:another/" + key)).isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> storage.read("minio:test-bucket/../secret")).isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> local.read("minio:test-bucket/" + key)).isInstanceOf(BusinessException.class).hasMessageContaining("启用");
    assertThat(requestPath.get()).isNull();
  }
}
