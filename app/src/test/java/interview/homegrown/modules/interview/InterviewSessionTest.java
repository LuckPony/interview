package interview.homegrown.modules.interview;


import interview.homegrown.modules.interview.model.InterviewSessionEntity;
import interview.homegrown.modules.interview.model.InterviewStatus;
import interview.homegrown.modules.interview.repository.InterviewSessionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// 面试会话 Repository 测试（使用 H2 内存库）
@DataJpaTest
public class InterviewSessionTest {

    @Autowired
    private InterviewSessionRepository sessionRepository;

    @Test
    @DisplayName("保存会话并按 id 查询")
    void shouldSaveAndFindById(){
        InterviewSessionEntity session = new InterviewSessionEntity();
        session.setId(UUID.randomUUID().toString());
        session.setSkillId("java-backend");
        session.setStatus(InterviewStatus.IN_PROGRESS);
        session.setTotalScore(90);
        session.setTotalQuestions(5);
        session.setCurrentQuestionIndex(3);

        sessionRepository.save(session);

        Optional<InterviewSessionEntity> found = sessionRepository.findById(session.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo(InterviewStatus.IN_PROGRESS);
        assertThat(found.get().getSkillId()).isEqualTo("java-backend");
    }
}
