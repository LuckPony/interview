package interview.homegrown.common.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.storage")
public class StorageProperties {
  public enum Mode { LOCAL, MINIO }
  private Mode mode = Mode.LOCAL;
  private String localDir = "./data/files";
  private String endpoint;
  private String accessKey;
  private String secretKey;
  private String bucket = "interview";
  private String region = "us-east-1";
}
