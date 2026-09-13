package interview.homegrown.modules.interview.repository;


import interview.homegrown.modules.interview.model.InterviewAnswerEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InterviewAnswerRepository extends JpaRepository<InterviewAnswerEntity,Long> {

    //按照会话 id 查找所有回答
    List<InterviewAnswerEntity> findBySessionIdOrderByQuestionIndex(String sessionId);

    // 按写入顺序（动态追问按时间追加）
    List<InterviewAnswerEntity> findBySessionIdOrderById(String sessionId);

    //按照会话 id 删除所有答案
    void deleteBySessionId(String sessionId);

    // 判断会话答案是否已经落库，保证结束/评估重复调用时不会生成重复问答。
    boolean existsBySessionId(String sessionId);
}
