package interview.homegrown.modules.interview.service;

import interview.homegrown.common.exception.BusinessException;
import interview.homegrown.common.exception.ErrorCode;
import interview.homegrown.modules.interview.model.InterviewSessionEntity;
import interview.homegrown.modules.interview.repository.InterviewSessionRepository;
import org.springframework.stereotype.Service;

/**
 * 面试会话持久化服务
 * 封装会话的新建、查询、更新
 */

@Service
public class InterviewPersistenceService {

    private final InterviewSessionRepository sessionRepository;
    public InterviewPersistenceService(InterviewSessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    //保存会话azSX
    public InterviewSessionEntity save(InterviewSessionEntity session){

        return sessionRepository.save(session);
    }

    //按照 Id 查会话,不存在抛出业务异常
    public InterviewSessionEntity getById(String sessionId){
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND,"sessionId="+sessionId));
    }
}
