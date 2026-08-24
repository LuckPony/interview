package interview.homegrown.modules.resume.service;

import interview.homegrown.common.exception.BusinessException;
import interview.homegrown.common.exception.ErrorCode;
import interview.homegrown.modules.resume.model.ResumeAnalysisEntity;
import interview.homegrown.modules.resume.model.ResumeDetailDTO;
import interview.homegrown.modules.resume.model.ResumeEntity;
import interview.homegrown.modules.resume.model.ResumeListItemDTO;
import interview.homegrown.modules.resume.repository.ResumeAnalysisRepository;
import interview.homegrown.modules.resume.repository.ResumeRepository;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

//简历查询服务,负责列表/详情查询以及entity --》 DTO映射
@Service
public class ResumeQueryService {

    private final ResumeRepository resumeRepository;
    private final ResumeAnalysisRepository resumeAnalysisRepository;

    public ResumeQueryService(ResumeRepository resumeRepository, ResumeAnalysisRepository resumeAnalysisRepository) {
        this.resumeAnalysisRepository = resumeAnalysisRepository;
        this.resumeRepository = resumeRepository;
    }

    //简历列表（带分析得分，仅当前用户）
    public List<ResumeListItemDTO> list(Long userId){

        List<ResumeEntity> resumes = resumeRepository.findByUserIdOrderByCreatedAtDesc(userId);
        if (resumes.isEmpty()){
            return List.of();
        }

        //批量查出所有分析结果
        List<Long> ids = resumes.stream().map(ResumeEntity::getId).toList();
        Map<Long, ResumeAnalysisEntity> analysisMap = resumeAnalysisRepository.findByResumeIdIn(ids).stream()
                .collect(Collectors.toMap(ResumeAnalysisEntity::getResumeId,a -> a));
        //拼接并转化为前端可以接受的形式
        return resumes.stream()
                .map(r -> toListItem(r,analysisMap.get(r.getId())))
                .toList();
    }

    //简历详情(含分析结果)
    public ResumeDetailDTO getDetail(Long userId, Long id){
        ResumeEntity resume = resumeRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_NOT_FOUND, "id= "+id));
        if (!userId.equals(resume.getUserId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该简历");
        }
        ResumeAnalysisEntity analysis = resumeAnalysisRepository.findByResumeId(id).orElse(null);
        return toDetail(resume,analysis);
    }

    // ===== Entity -> DTO 映射 =====
    private ResumeListItemDTO toListItem(ResumeEntity r, ResumeAnalysisEntity a) {
        return new ResumeListItemDTO(
                r.getId(),
                r.getOriginalName(),
                r.getFileType(),
                r.getFileSize(),
                r.getStatus().name(),
                a != null ?a.getOverallScore() : null,
                r.getCreatedAt()
        );
    }
    private ResumeDetailDTO toDetail(ResumeEntity r,ResumeAnalysisEntity a){

        return new ResumeDetailDTO(
                r.getId(),
                r.getOriginalName(),
                r.getFileType(),
                r.getFileSize(),
                r.getStorageKey(),
                r.getStatus().name(),
                r.getErrorMessage(),
                r.getResumeText(),
                a!=null ? a.getOverallScore() : null,
                a!=null ? a.getSummary() : null,
                a!=null ? splitToList(a.getStrengths()) : List.of(),
                a!=null ? splitToList(a.getWeaknesses()) : List.of(),
                a!=null ? splitToList(a.getSuggestions()) : List.of(),
                r.getCreatedAt()
        );
    }

    //数据库中无List格式，是以逗号分隔字符串存储的，因此要拆成List
    private List<String> splitToList(String s){
        if(s == null || s.isBlank()){
            return List.of();
        }
        return Arrays.stream(s.split(","))
                .map(String :: trim)
                .filter(x -> !x.isBlank())
                .toList();
    }


}
