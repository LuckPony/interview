package interview.homegrown.common.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfig {
  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(name = "app.storage.mode", havingValue = "minio")
  public S3Client originalFileS3Client(StorageProperties config) {
    if (blank(config.getEndpoint()) || blank(config.getAccessKey()) || blank(config.getSecretKey()) || blank(config.getBucket())) {
      throw new IllegalStateException("MinIO 模式需要配置 APP_STORAGE_ENDPOINT / ACCESS_KEY / SECRET_KEY / BUCKET");
    }
    URI endpoint = URI.create(config.getEndpoint());
    if (!("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme())) || endpoint.getHost() == null
        || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null) {
      throw new IllegalStateException("APP_STORAGE_ENDPOINT 必须是有效的 HTTP(S) 服务地址");
    }
    return S3Client.builder().endpointOverride(endpoint).region(Region.of(config.getRegion()))
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(config.getAccessKey(), config.getSecretKey())))
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).chunkedEncodingEnabled(false).build())
        .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(5)).socketTimeout(Duration.ofSeconds(30)))
        .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(60)).apiCallAttemptTimeout(Duration.ofSeconds(30)))
        .build();
  }
  private static boolean blank(String text) { return text == null || text.isBlank(); }
}
