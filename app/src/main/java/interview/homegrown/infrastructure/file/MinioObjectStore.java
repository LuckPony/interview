package interview.homegrown.infrastructure.file;

import interview.homegrown.common.config.StorageProperties;
import interview.homegrown.common.exception.BusinessException;
import interview.homegrown.common.exception.ErrorCode;
import java.io.InputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** 私有桶原文件存储。浏览器通过已鉴权的应用预览接口读取，不暴露桶密钥或内网地址。 */
@Component
@ConditionalOnProperty(name = "app.storage.mode", havingValue = "minio")
public class MinioObjectStore {
  private static final Logger log = LoggerFactory.getLogger(MinioObjectStore.class);
  private final S3Client client;
  private final String bucket;

  public MinioObjectStore(S3Client client, StorageProperties properties) {
    this.client = client;
    this.bucket = properties.getBucket();
  }

  public String upload(String key, byte[] bytes, String mimeType) {
    try {
      client.putObject(r -> r.bucket(bucket).key(key).contentType(mimeType), RequestBody.fromBytes(bytes));
      return "minio:" + bucket + "/" + key;
    } catch (SdkException e) { throw failure(e); }
  }

  public Resource read(String reference) {
    String key = objectKey(reference);
    try {
      long length = client.headObject(r -> r.bucket(bucket).key(key)).contentLength();
      return new AbstractResource() {
        @Override public String getDescription() { return "MinIO original file"; }
        @Override public String getFilename() { return key; }
        @Override public long contentLength() { return length; }
        @Override public InputStream getInputStream() {
          try { return client.getObject(r -> r.bucket(bucket).key(key)); }
          catch (SdkException e) { throw failure(e); }
        }
      };
    } catch (SdkException e) { throw failure(e); }
  }

  public void delete(String reference) {
    String key = objectKey(reference);
    try { client.deleteObject(r -> r.bucket(bucket).key(key)); }
    catch (SdkException e) { throw failure(e); }
  }

  private String objectKey(String reference) {
    String prefix = "minio:" + bucket + "/";
    if (!reference.startsWith(prefix)) throw new BusinessException(ErrorCode.BAD_REQUEST, "原件所属存储桶与当前配置不一致，请检查存储配置");
    String key = reference.substring(prefix.length());
    if (!key.matches("[a-f0-9-]{36}(\\.[A-Za-z0-9]+)?")) throw new BusinessException(ErrorCode.BAD_REQUEST, "原文件标识无效");
    return key;
  }

  private BusinessException failure(SdkException error) {
    if (error instanceof S3Exception s3 && s3.statusCode() == 404) {
      return new BusinessException(ErrorCode.NOT_FOUND, "原文件或存储桶不存在，请确认 MinIO 配置及文件是否保留");
    }
    log.warn("MinIO 原文件操作失败", error);
    return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "文件存储暂不可用，请检查 MinIO 连接及访问权限");
  }
}
