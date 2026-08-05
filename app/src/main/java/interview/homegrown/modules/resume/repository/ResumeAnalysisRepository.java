package interview.homegrown.modules.resume.repository;

import interview.homegrown.modules.resume.model.ResumeAnalysisEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ResumeAnalysisRepository extends JpaRepository<ResumeAnalysisEntity, Long> {

    //按照简历id查找分析结果
    Optional<ResumeAnalysisEntity> findByResumeId(Long resumeId);

    //查找简历id是否有分析结果
    Boolean existsByResumeId(Long resumeId);

    //批量查多个简历的分析结果（避免N+1查询）
    List<ResumeAnalysisEntity> findByResumeIdIn(List<Long> resumeId);

    //根据简历ID删除所有分析报告
    void deleteByResumeId(Long resumeId);
}