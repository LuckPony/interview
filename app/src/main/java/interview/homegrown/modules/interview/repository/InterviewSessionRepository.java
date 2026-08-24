package interview.homegrown.modules.interview.repository;


import interview.homegrown.modules.interview.model.InterviewSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InterviewSessionRepository extends JpaRepository<InterviewSessionEntity,String> {

    //按照创建时间倒序获取所有会话
    List<InterviewSessionEntity> findAllByOrderByCreatedAtDesc();

    //当前用户的所有会话（按创建时间倒序）
    List<InterviewSessionEntity> findByUserIdOrderByCreatedAtDesc(Long userId);

    //按照简历id查找对应会话
    Optional<InterviewSessionEntity> findByResumeIdOrderByCreatedAtDesc(Long resumeId);
}
