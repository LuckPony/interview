package interview.homegrown.infrastructure.file;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import interview.homegrown.common.config.StorageProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import interview.homegrown.common.exception.BusinessException;
import interview.homegrown.common.exception.ErrorCode;
import org.springframework.core.io.Resource;
import org.springframework.core.io.FileSystemResource;

/**
 * 简历和知识库共用的原文件存储入口。
 * <p>本地模式写磁盘；MinIO 模式写私有桶。带 minio: 前缀的 key 走对象存储，
 * 历史磁盘 key 仍从本地目录读取，避免切换存储模式后误读旧文件。</p>
 */
@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);

    private final Path root;
    private final MinioObjectStore minio;

    @Autowired
    public FileStorageService(StorageProperties config, ObjectProvider<MinioObjectStore> minio) {
        this(config.getLocalDir(), minio.getIfAvailable());
    }

    public FileStorageService(String localDir) { this(localDir, null); }

    FileStorageService(String localDir, MinioObjectStore minio) {
        this.minio = minio;
        this.root = Paths.get(localDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建本地存储目录: " + root, e);
        }
    }

    /**
     * 保存文件到配置的存储后端，返回唯一存储 key。
     */
    public String upload(byte[] bytes, String fileName, String mimeType) {
        String ext = "";
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            ext = fileName.substring(dotIndex);
        }
        String key = UUID.randomUUID() + ext;
        if (minio != null) return minio.upload(key, bytes, mimeType);
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
        if (key.startsWith("minio:")) throw new BusinessException(ErrorCode.BAD_REQUEST, "私有 MinIO 原文件需通过鉴权预览接口访问");
        try {
            return root.resolve(key).toUri().toURL();
        } catch (Exception e) {
            throw new IllegalStateException("无法构造文件 URL: " + e.getMessage(), e);
        }
    }

    /** 仅按后端保存的相对 key 读取，不接受用户传入任意磁盘路径。 */
    public Resource read(String key) {
        if (key == null || key.isBlank()) throw new BusinessException(ErrorCode.NOT_FOUND, "未保存原文件，请重新上传原件");
        if (key.startsWith("minio:")) {
            if (minio == null) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "该原件保存在 MinIO，请启用 MinIO 存储配置");
            return minio.read(key);
        }
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root) || !Files.isRegularFile(target) || Files.isSymbolicLink(target)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "原文件不存在，请检查存储目录或重新导入");
        }
        return new FileSystemResource(target);
    }

    /** 删除服务端生成的单个存储 key；不接受目录或任意路径。清理失败保留文件并记录。 */
    public void delete(String key) {
        if (key != null && key.startsWith("minio:")) {
            try {
                if (minio != null) minio.delete(key);
                else log.warn("MinIO 未启用，未清理原文件: {}", key);
            } catch (BusinessException e) { log.warn("原文件清理失败: {}", key, e); }
            return;
        }
        if (key == null || !key.matches("[a-f0-9-]{36}(\\.[A-Za-z0-9]+)?")) return;
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root) || Files.isSymbolicLink(target)) return;
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            log.warn("存储文件清理失败: key={}", key, e);
        }
    }
}
