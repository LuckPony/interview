package interview.homegrown.modules.interview;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.homegrown.common.exception.BusinessException;
import interview.homegrown.infrastructure.redis.RedisService;
import interview.homegrown.modules.drill.domain.StudyPlan;
import interview.homegrown.modules.drill.repository.ConceptRepository;
import interview.homegrown.modules.drill.repository.StudyPlanRepository;
import interview.homegrown.modules.drill.service.CorpusLibraryService;
import interview.homegrown.modules.interview.model.CreateSessionRequest;
import interview.homegrown.modules.interview.model.InterviewDifficulty;
import interview.homegrown.modules.interview.model.InterviewQuestionResult;
import interview.homegrown.modules.interview.model.InterviewSessionEntity;
import interview.homegrown.modules.interview.repository.InterviewAnswerRepository;
import interview.homegrown.modules.interview.repository.InterviewQuestionRepository;
import interview.homegrown.modules.interview.repository.InterviewSessionRepository;
import interview.homegrown.modules.interview.service.FollowupGeneratorService;
import interview.homegrown.modules.interview.service.InterviewEvaluateService;
import interview.homegrown.modules.interview.service.InterviewPersistenceService;
import interview.homegrown.modules.interview.service.InterviewQuestionService;
import interview.homegrown.modules.interview.service.InterviewSessionService;
import interview.homegrown.modules.interview.service.InterviewSkillService;
import interview.homegrown.modules.resume.repository.ResumeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

class InterviewCorpusTest {
  final CorpusLibraryService library = mock(CorpusLibraryService.class);
  final StudyPlanRepository plans = mock(StudyPlanRepository.class);
  final InterviewQuestionService questions = mock(InterviewQuestionService.class);
  final InterviewPersistenceService persistence = mock(InterviewPersistenceService.class);
  final InterviewSessionService service = new InterviewSessionService(mock(ResumeRepository.class), mock(InterviewSkillService.class),
      mock(InterviewSessionRepository.class), persistence, questions, mock(FollowupGeneratorService.class), mock(InterviewEvaluateService.class),
      mock(InterviewAnswerRepository.class), mock(ConceptRepository.class), mock(InterviewQuestionRepository.class), mock(RedisService.class),
      new ObjectMapper(), library, plans);

  @Test @DisplayName("只选择知识库也能创建面试，索引依据传给出题服务并保存资料关联")
  void corpusOnly() {
    when(library.reference(42L, 7L)).thenReturn("[来源 S1] Java 内存模型");
    when(questions.generateBaseQuestions("", InterviewDifficulty.MIDDLE, "", List.of(), false, null, "[来源 S1] Java 内存模型"))
        .thenReturn(new InterviewQuestionResult(List.of(new InterviewQuestionResult.InterviewQuestion("请介绍自己", List.of()))));
    var result = service.createSession(new CreateSessionRequest(null, null, null, null, List.of(), "TEXT", null, 42L), 7L);
    var saved = ArgumentCaptor.forClass(InterviewSessionEntity.class); verify(persistence).save(saved.capture());
    assertThat(saved.getValue().getCorpusId()).isEqualTo(42L);
    assertThat(saved.getValue().getUserId()).isEqualTo(7L);
    assertThat(result.id()).isEqualTo(saved.getValue().getId());
  }

  @Test @DisplayName("跨用户学习计划在任何模型调用和保存之前拒绝")
  void rejectsForeignPlan() {
    StudyPlan other = new StudyPlan(); other.setId(20L); other.setUserId(8L);
    when(plans.findAllById(List.of(20L))).thenReturn(List.of(other));
    assertThatThrownBy(() -> service.createSession(new CreateSessionRequest(null, null, null, null, List.of(20L), "TEXT", null, null), 7L))
        .isInstanceOf(BusinessException.class);
    verifyNoInteractions(questions, persistence);
  }
}
