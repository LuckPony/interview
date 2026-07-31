package interview.homegrown.infrastructure.file;


import interview.homegrown.common.exception.BusinessException;
import interview.homegrown.common.exception.ErrorCode;
import org.springframework.stereotype.Service;

/**
 * 文件校验服务
 * 1. 校验文件非空
 * 2. 校验文件大小不超过限制
 * 3. 基于字节内容检测真实类型，校验是否在白名单内
 */
@Service
public class FileValidationService {

    private final FileProperties fileProperties;
    private final DocumentParseService documentParseService;
    public FileValidationService(FileProperties fileProperties,DocumentParseService documentParseService) {
        this.fileProperties = fileProperties;
        this.documentParseService = documentParseService;
    }

    /**
     * 校验文件，返回检测到的 MIME 类型
     * @throws BusinessException 文件过大 / 类型不支持 / 内容为空
     */
    public String validate(byte[] bytes, String originalName){

        if(bytes == null || bytes.length == 0){
            throw new BusinessException(ErrorCode.BAD_REQUEST,"文件内容为空："+ originalName);
        }

        if(bytes.length > fileProperties.getMaxSize()){
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE,"实际: " + bytes.length + "字节，上限：" + fileProperties.getMaxSize());
        }

        String detectedType = documentParseService.detectContectType(bytes);
        if(!fileProperties.getAllowedTypes().contains(detectedType)){
            throw new BusinessException(ErrorCode.FILE_TYPE_NOT_SUPPORTED, "检测到类型：" + detectedType + "不符合要求");
        }

        return detectedType;
    }

}
