package interview.homegrown.infrastructure.file;


import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 文件上传配置
 * 绑定 application.yml 中 app.file.* 的配置项
 */
@Configuration
@ConfigurationProperties(prefix = "app.file")
public class FileProperties {

    //单个文件最大字节数设置为50MB
    private long maxSize = 50 * 1024 * 1024L;

    //允许上传的MIME类型,默认如下，实际的要去app.file定义
    private List<String> allowedTypes = List.of(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "text/plain"
    );


    public long getMaxSize() {
        return maxSize;
    }

    public void setMaxSize(long maxSize) {
        this.maxSize = maxSize;
    }

    public List<String> getAllowedTypes() {
        return allowedTypes;
    }

    public void setAllowedTypes(List<String> allowedTypes) {
        this.allowedTypes = allowedTypes;
    }
}
