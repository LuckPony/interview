package interview.homegrown.infrastructure.file;


import interview.homegrown.common.config.AppConfigProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

/**
 * S3 客户端 Bean 配置
 * 对接 MinIO 或其他 S3 兼容对象存储
 */
@Configuration
public class StorageConfigProperties {

    @Bean
    public S3Client s3Client(AppConfigProperties appConfig){

        var storage = appConfig.getStorage();
        return S3Client.builder()
                .endpointOverride(URI.create(storage.getEndpoint()))
                .region(Region.of(storage.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(storage.getAccessKey(),storage.getSecretKey())
                ))
                .forcePathStyle(true)  // MinIO 必须启用
                .build();

    }
}
