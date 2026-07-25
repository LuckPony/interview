package interview.homegrown.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 应用自定义配置
 * 绑定 application.yml 中 app.* 前缀的配置项
 */

@Configuration
@ConfigurationProperties(prefix = "app" )
public class AppConfigProperties {

    private final Storage storage = new Storage();

    public Storage getStorage(){
        return storage;
    }

    public static class Storage{

        //S3兼容存储的Endpoint
        private String endpoint = "http://localhost:9000";

        //访问密钥
        private String accessKey = "minioadmin";

        //秘密密钥
        private String secretKey = "minioadmin";

        //存储桶名称
        private String bucket = "interview-homegrown";

        //区域
        private String region = "us-east-1";


        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getAccessKey() {
            return accessKey;
        }

        public void setAccessKey(String accessKey) {
            this.accessKey = accessKey;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }

        public String getBucket() {
            return bucket;
        }

        public void setBucket(String bucket) {
            this.bucket = bucket;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }
    }




}
