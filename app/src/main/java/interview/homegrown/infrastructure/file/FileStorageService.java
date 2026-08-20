package interview.homegrown.infrastructure.file;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * 本地文件系统存储（替代原 S3/MinIO 对象存储）。
 *
 * <p>单机自包含：文件写入本地目录，返回相对 key；getUrl 返回 file:// URL。
 * 核心学习功能不依赖它；简历/知识库上传走这里。API 与旧 S3 版本保持一致，
 * 调用方（ResumeUploadService / HealthController / FileDemoController）无需改动。</p>
 */
@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);

    private final Path root;

    public FileStorageService(@Value("${app.storage.local-dir:./data/files}") String localDir) {
        this.root = Paths.get(localDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建本地存储目录: " + root, e);
        }
    }

    /**
     * 保存文件到本地，返回唯一存储 key。
     */
    public String upload(byte[] bytes, String fileName, String mimeType) {
        String ext = "";
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            ext = fileName.substring(dotIndex);
        }
        String key = UUID.randomUUID() + ext;
        try {
            Files.write(root.resolve(key), bytes);
        } catch (IOException e) {
            throw new IllegalStateException("本地文件写入失败: " + e.getMessage(), e);
        }
        log.info("本地文件保存成功: key={}", key);
        return key;
    }

    /**
     * 返回文件的 file:// URL。
     */
    public URL getUrl(String key) {
        try {
            return root.resolve(key).toUri().toURL();
        } catch (Exception e) {
            throw new IllegalStateException("无法构造文件 URL: " + e.getMessage(), e);
        }
    }
}
