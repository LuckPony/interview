package interview.homegrown.modules.interview.repository;


import interview.homegrown.modules.interview.model.InterviewAnswerEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InterviewAnswerRepository extends JpaRepository<InterviewAnswerEntity,Long> {

    //按照会话 id 查找所有回答
    List<InterviewAnswerEntity> findBySessionIdOrderByQuestionIndex(String sessionId);

    //按照会话 id 删除所有答案
    void deleteBySessionId(String sessionId);
}
