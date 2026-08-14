package interview.homegrown.infrastructure.file;


import interview.homegrown.common.config.AppConfigProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetUrlRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URL;
import java.util.UUID;

/**
 * S3 兼容对象存储服务
 * 用于存储简历文件、知识库文档等二进制内容
 */
@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);
    private final S3Client s3Client;
    private final String bucket;
    /** 对象存储是否可用。MinIO 未启动时应用照常启动，仅文件上传类功能受限。 */
    private volatile boolean storageAvailable = true;

    public FileStorageService(S3Client s3Client, AppConfigProperties appConfig) {
        this.s3Client = s3Client;
        this.bucket = appConfig.getStorage().getBucket();
        try {
            ensureBucketExists();
        } catch (Exception e) {
            // 对象存储不可用不阻塞应用启动：核心学习功能（练习/复习/每日任务等）不依赖 MinIO。
            storageAvailable = false;
            log.warn("对象存储（MinIO/S3）未就绪，文件上传功能暂不可用，核心功能不受影响：{}", e.getMessage());
        }
    }

    /**
     * 上传文件
     *
     * @param bytes    文件字节
     * @param fileName 原始文件名（用于推断扩展名）
     * @param mimeType 媒体类型
     * @return 存储的唯一 Key
     */
    public String upload(byte[] bytes, String fileName, String mimeType) {
        // 惰性重试：MinIO 恢复后，首次上传时自动重新初始化，无需重启应用。
        if (!storageAvailable) {
            synchronized (this) {
                if (!storageAvailable) {
                    try {
                        ensureBucketExists();
                        storageAvailable = true;
                        log.info("对象存储已就绪，文件上传能力恢复");
                    } catch (Exception e) {
                        throw new IllegalStateException("对象存储未就绪（MinIO 未启动？），无法上传文件：" + e.getMessage());
                    }
                }
            }
        }
        //生成唯一存储路径：UUID + 原始拓展名
        String ext = "";
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            ext = fileName.substring(dotIndex);
        }
        String key = UUID.randomUUID() + ext;

        var request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(mimeType)
                .contentLength((long) bytes.length)
                .build();
        s3Client.putObject(request, RequestBody.fromBytes(bytes));
        log.info("文件上传成功：bucket={},key={}",bucket,key);
        return key;
    }

    /**
     * 获取文件的公开访问 URL
     */
    public URL getUrl(String key) {
        return s3Client.utilities().getUrl(GetUrlRequest.builder()
                .bucket(bucket)
                .key(key)
                .build()
        );
    }

    /**
     * 检查存储桶是否存在，不存在则自动创建
     */
    private void ensureBucketExists() {
        try{
            s3Client.headBucket(b -> b.bucket(bucket));
            log.info("存储桶已存在{}",bucket);
        }catch (NoSuchBucketException e){
            s3Client.createBucket(b -> b.bucket(bucket));
            log.info("存储桶已创建：{}",bucket);
        }
    }


}
