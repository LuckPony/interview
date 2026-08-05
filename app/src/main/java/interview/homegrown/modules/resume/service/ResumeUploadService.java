package interview.homegrown.modules.resume.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.homegrown.common.exception.BusinessException;
import interview.homegrown.common.exception.ErrorCode;
import interview.homegrown.infrastructure.file.DocumentParseService;
import interview.homegrown.infrastructure.file.FileHashService;
import interview.homegrown.infrastructure.file.FileStorageService;
import interview.homegrown.infrastructure.file.FileValidationService;
import interview.homegrown.modules.resume.model.*;
import interview.homegrown.modules.resume.repository.ResumeAnalysisRepository;
import interview.homegrown.modules.resume.repository.ResumeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;


//简历上传服务---整个项目的第一个业务闭环
//流水线：校验——哈希去重——S3存储——文本解析——落库ProgreSQL——AI分析——更新状态
@Service
public class ResumeUploadService {

    private static final Logger log = LoggerFactory.getLogger(ResumeUploadService.class);

    private final FileValidationService fileValidationService;
    private final FileHashService fileHashService;
    private final FileStorageService fileStorageService;
    private final DocumentParseService documentParseService;
    private final ResumeRepository resumeRepository;
    private final ResumeAnalysisRepository resumeAnalysisRepository;
    private final ResumeQueryService resumeQueryService;
    private final ResumeAnalysisService resumeAnalysisService;
    //负责在 java对象 和 JSON 字符串之间进行转换
    private final ObjectMapper objectMapper;

    public ResumeUploadService(
            FileValidationService fileValidationService,
            FileHashService fileHashService,
            FileStorageService fileStorageService,
            DocumentParseService documentParseService,
            ResumeRepository resumeRepository,
            ResumeAnalysisRepository resumeAnalysisRepository,
            ResumeQueryService resumeQueryService,
            ResumeAnalysisService resumeAnalysisService,
            ObjectMapper objectMapper
    ){
        this.fileValidationService = fileValidationService;
        this.fileHashService = fileHashService;
        this.fileStorageService = fileStorageService;
        this.documentParseService = documentParseService;
        this.resumeRepository = resumeRepository;
        this.resumeAnalysisRepository = resumeAnalysisRepository;
        this.resumeQueryService = resumeQueryService;
        this.resumeAnalysisService = resumeAnalysisService;
        this.objectMapper = objectMapper;
    }

    //上传同步简历
    public ResumeDetailDTO upload(byte[] bytes,String originalName){

        //校验文件大小以及是否在类型白名单
        String contentType = fileValidationService.validate(bytes,originalName);
        //计算存储位置的hash值
        String hash = fileHashService.computesha256(bytes);
        //内容去重
        resumeRepository.findByContentHash(hash).ifPresent(existing -> {
            throw new BusinessException(ErrorCode.DUPLICATE_FILE, "已存在相同内容的简历 id = " + existing.getId());
        });
        //存储到MinIO（S3）
        String storageKey = fileStorageService.upload(bytes,originalName,contentType);
        //Tika 解析纯文本
        String resumeText = documentParseService.parseText(bytes,originalName);
        //落库
        ResumeEntity resume = new ResumeEntity();
        resume.setOriginalName(originalName);
        resume.setFileType(contentType);
        resume.setFileSize((long) bytes.length);
        resume.setStorageKey(storageKey);
        resume.setContentHash(hash);
        resume.setResumeText(resumeText);
        resume.setStatus(ResumeStatus.ANALYZING);

        resume = resumeRepository.save(resume);

        //同步调用LLM分析。   注意：注意：这里刻意不用 @Transactional 包住整个方法， 避免 AI 调用（耗时长）占用数据库事务连接。
        //原项目 AGENTS.md 里有一条硬规则——"LLM、S3、外部 HTTP 调用不得放在数据库事务内"
        try{
            ResumeAnalysisResult analysis = resumeAnalysisService.analyze(resumeText);
            saveAnalysis(resume.getId(), analysis);
            resume.setStatus(ResumeStatus.COMPLETED);
            log.info("简历分析成功: id={}, score={}", resume.getId(), analysis.overallScore());
        }catch (Exception e){
            log.error("简历分析失败: id={}", resume.getId(), e);
            resume.setStatus(ResumeStatus.FAILED);
            resume.setErrorMessage(e.getMessage());
            resumeRepository.save(resume);
            throw new BusinessException(ErrorCode.RESUME_ANALYSIS_FAILED,e.getMessage());
        }
        resumeRepository.save(resume);

        return resumeQueryService.getDetail(resume.getId());

    }

    //保存结果到resume表
    private void saveAnalysis(Long resumeId,ResumeAnalysisResult analysis){
        ResumeAnalysisEntity entity = new ResumeAnalysisEntity();
        entity.setResumeId(resumeId);
        entity.setOverallScore(analysis.overallScore());
        entity.setSummary(analysis.summary());
        entity.setStrengths(String.join(",",analysis.strengths()));
        entity.setWeaknesses(String.join(",",analysis.weaknesses()));
        entity.setSuggestions(String.join(",",analysis.suggestions()));
        try{
            entity.setRawJson(objectMapper.writeValueAsString(analysis));
        }catch(JsonProcessingException e){
            log.warn("分析结果序列化为JSON过程失败",e);
        }
        resumeAnalysisRepository.save(entity);
    }

}
