package interview.homegrown.modules.resume.service;


import interview.homegrown.common.exception.BusinessException;
import interview.homegrown.common.exception.ErrorCode;
import interview.homegrown.modules.resume.model.ResumeEntity;
import interview.homegrown.modules.resume.repository.ResumeRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

@Service
public class ResumeDeleteService {

    private final ResumeRepository resumeRepository;


    public ResumeDeleteService(ResumeRepository resumeRepository) {

        this.resumeRepository = resumeRepository;
    }

    //根据id删除简历，同时同步删除简历分析表中对应的所有记录（校验归属）
    @Transactional(rollbackOn = Exception.class)
    public void delete(Long userId, Long id){

        //先查找，找到了再删除
        ResumeEntity resume = resumeRepository.findById(id).orElseThrow(
                () -> new BusinessException(ErrorCode.RESUME_NOT_FOUND, "简历不存在，无法删除！")
        );
        if (!userId.equals(resume.getUserId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权删除该简历");
        }
        resumeRepository.deleteById(id);
    }
}
