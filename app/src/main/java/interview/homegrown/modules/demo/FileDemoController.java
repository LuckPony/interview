package interview.homegrown.modules.demo;

import interview.homegrown.common.exception.BusinessException;
import interview.homegrown.common.exception.ErrorCode;
import interview.homegrown.common.result.Result;
import interview.homegrown.infrastructure.file.DocumentParseService;
import interview.homegrown.infrastructure.file.FileHashService;
import interview.homegrown.infrastructure.file.FileStorageService;
import interview.homegrown.infrastructure.file.FileValidationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Objects;


/**
 * 文件处理演示 Controller
 * 验证：上传 -> 校验 -> 哈希 -> S3 存储 -> 文本解析 全链路
 */
@RestController
@RequestMapping("api/demo/file")
@Tag(name = "文件处理演示",description = "验证 文件上传、类型检测、哈希去重、S3 存储、文本解析")
public class FileDemoController {

    private static final Logger log = LoggerFactory.getLogger(FileDemoController.class);

    private static final int PRIVIEW_LENGTH = 1000;

    private final FileStorageService fileStorageService;
    private final FileValidationService fileValidationService;
    private final FileHashService fileHashService;
    private final DocumentParseService documentParseService;

    public FileDemoController(FileValidationService fileValidationService, FileHashService fileHashService,FileStorageService fileStorageService, DocumentParseService documentParseService) {
        this.fileHashService = fileHashService;
        this.fileValidationService = fileValidationService;
        this.fileStorageService = fileStorageService;
        this.documentParseService = documentParseService;
    }

    //上传并解析文件：完整走一遍文件处理流水线(上传文件必须使用--MULTIPART_FORM_DATA_VALUE--格式)
    @PostMapping(value = "/upload",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "上传并解析文件",description = "上传 PDF/DOCX/TXT 文件，自动完成：类型检测 -> 内容哈希 -> S3 存储 -> Tika 文本解析")
    //非json数据不能用RequestBody，获取文件格式要用RequestParam
    public Result<FileParseResult> upload(
            @Parameter(description = "待解析的文件（PDF/DOCX/TXT）")
            @RequestParam("file")MultipartFile file
            ){

        try{
            byte[] bytes = file.getBytes();

            String contentType = fileValidationService.validate(bytes, file.getOriginalFilename());

            String hash = fileHashService.computesha256(bytes);

            String storageKey = fileStorageService.upload(bytes, Objects.requireNonNull(file.getOriginalFilename()),contentType);

            String parseText = documentParseService.parseText(bytes,file.getOriginalFilename());
            String preview = parseText.length() > PRIVIEW_LENGTH ? parseText.substring(0, PRIVIEW_LENGTH) + "...(截断)" : parseText;

            FileParseResult result = new FileParseResult(
                    file.getOriginalFilename(),
                    contentType,
                    file.getSize(),
                    hash,
                    storageKey,
                    parseText.length(),
                    preview
            );

            log.info("文件处理成功: name={}, type={}, size={}, hash={}, storageKey={}",
                    result.originalName(), result.contentType(), result.size(),
                    result.contentHash(), result.storageKey());
            return Result.success(result);

        }catch(IOException e){
            log.error("读取上传文件失败",e);
            throw new BusinessException(ErrorCode.FILE_PARSE_FAILED,"读取文件失败");
        }

    }


    public record FileParseResult(
            String originalName,
            String contentType,
            long size,
            String contentHash,
            String storageKey,
            int textLength,
            String previewText
    ){}
}


