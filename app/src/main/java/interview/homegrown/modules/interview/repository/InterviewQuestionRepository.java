package interview.homegrown.modules.interview.repository;

import interview.homegrown.modules.interview.model.InterviewQuestionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface InterviewQuestionRepository extends JpaRepository<InterviewQuestionEntity, String> {

    // 派生删除需要事务；completeInterview 在 LLM 评估之后清理题目记录，
    // 不能把评估包进大事务，这里方法级单独开事务。
    @Transactional
    void deleteBySessionId(String sessionId);
}
