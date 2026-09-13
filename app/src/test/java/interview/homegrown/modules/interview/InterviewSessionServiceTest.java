package interview.homegrown.modules.interview;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.homegrown.infrastructure.redis.RedisService;
import interview.homegrown.modules.drill.repository.ConceptRepository;
import interview.homegrown.modules.drill.repository.StudyPlanRepository;
import interview.homegrown.modules.drill.service.CorpusLibraryService;
import interview.homegrown.modules.interview.model.InterviewAnswerEntity;
import interview.homegrown.modules.interview.model.InterviewDifficulty;
import interview.homegrown.modules.interview.model.InterviewSessionEntity;
import interview.homegrown.modules.interview.model.InterviewStatus;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InterviewSessionServiceTest {

    @Mock private ResumeRepository resumeRepository;
    @Mock private InterviewSkillService skillService;
    @Mock private InterviewSessionRepository sessionRepository;
    @Mock private InterviewPersistenceService persistenceService;
    @Mock private InterviewQuestionService questionService;
    @Mock private FollowupGeneratorService followupService;
    @Mock private InterviewEvaluateService evaluateService;
    @Mock private InterviewAnswerRepository answerRepository;
    @Mock private ConceptRepository conceptRepository;
    @Mock private InterviewQuestionRepository questionRepository;
    @Mock private RedisService redisService;
    @Mock private CorpusLibraryService libraryService;
    @Mock private StudyPlanRepository studyPlanRepository;

    private ObjectMapper objectMapper;
    private InterviewSessionService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new InterviewSessionService(
                resumeRepository,
                skillService,
                sessionRepository,
                persistenceService,
                questionService,
                followupService,
                evaluateService,
                answerRepository,
                conceptRepository,
                questionRepository,
                redisService,
                objectMapper,
                libraryService,
                studyPlanRepository);
    }

    @Test
    @DisplayName("主动退出立即结束并保存已提交回答，但不触发评估")
    void shouldFinishWithoutEvaluation() throws Exception {
        InterviewSessionEntity session = session("session-1");
        var qa = List.of(
                new InterviewSessionService.QAItem("请介绍一下自己", "这是我的回答", false, 0, 0),
                new InterviewSessionService.QAItem("第二题", null, false, 1, 0));

        when(persistenceService.getById("session-1")).thenReturn(session);
        when(redisService.get("interview:qa:session-1"))
                .thenReturn(Optional.of(objectMapper.writeValueAsString(qa)));
        when(answerRepository.existsBySessionId("session-1")).thenReturn(false);
        when(answerRepository.findBySessionIdOrderById("session-1")).thenReturn(List.of());

        var result = service.finishWithoutEvaluation("session-1", 7L);

        assertThat(result.status()).isEqualTo(InterviewStatus.PENDING_EVALUATION);
        assertThat(result.createdAt()).isEqualTo(Instant.parse("2026-09-13T03:17:00Z"));
        assertThat(session.getStatus()).isEqualTo(InterviewStatus.PENDING_EVALUATION);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<InterviewAnswerEntity>> answers = ArgumentCaptor.forClass(List.class);
        verify(answerRepository).saveAll(answers.capture());
        assertThat(answers.getValue()).hasSize(1);
        assertThat(answers.getValue().get(0).getAnswerText()).isEqualTo("这是我的回答");
        verify(redisService, atLeastOnce()).set("interview:finished:session-1", "true");
        verify(persistenceService).save(session);
        verifyNoInteractions(evaluateService);
    }

    private InterviewSessionEntity session(String id) {
        InterviewSessionEntity session = new InterviewSessionEntity();
        session.setId(id);
        session.setUserId(7L);
        session.setDifficulty(InterviewDifficulty.MIDDLE);
        session.setStatus(InterviewStatus.IN_PROGRESS);
        session.setTotalQuestions(6);
        session.setCurrentQuestionIndex(1);
        session.setMode("TEXT");
        session.setDurationMin(60);
        session.setStartAt(LocalDateTime.now(ZoneOffset.UTC));
        session.setCreatedAt(LocalDateTime.of(2026, 9, 13, 3, 17));
        return session;
    }
}
