package interview.homegrown.modules.interview.repository;

import interview.homegrown.modules.interview.model.InterviewQuestionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InterviewQuestionRepository extends JpaRepository<InterviewQuestionEntity, String> {

    void deleteBySessionId(String sessionId);
}
