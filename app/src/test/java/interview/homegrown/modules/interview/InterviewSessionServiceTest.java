package interview.homegrown.modules.interview;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.homegrown.infrastructure.redis.RedisService;
import interview.homegrown.modules.drill.repository.ConceptRepository;
import interview.homegrown.modules.drill.repository.StudyPlanRepository;
import interview.homegrown.modules.drill.service.CorpusLibraryService;
import interview.homegrown.modules.interview.model.InterviewAnswerEntity;
import interview.homegrown.modules.interview.model.InterviewDifficulty;
import interview.homegrown.modules.interview.model.InterviewQuestionEntity;
import interview.homegrown.modules.interview.model.InterviewQuestionResult;
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
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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

    @Test
    @DisplayName("异常中断只落库部分回答时能够补写剩余回答且不重复")
    void shouldPersistOnlyMissingAnswers() throws Exception {
        InterviewSessionEntity session = session("session-partial");
        var qa = List.of(
                new InterviewSessionService.QAItem("问题一", "回答一", false, 0, 0),
                new InterviewSessionService.QAItem("追问一", "回答二", true, 0, 1));
        InterviewAnswerEntity existing = answer(1L, "session-partial", 0, "问题一", "回答一", false);

        when(persistenceService.getById("session-partial")).thenReturn(session);
        when(redisService.get("interview:qa:session-partial"))
                .thenReturn(Optional.of(objectMapper.writeValueAsString(qa)));
        when(answerRepository.findBySessionIdOrderById("session-partial")).thenReturn(List.of(existing));

        service.finishWithoutEvaluation("session-partial", 7L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<InterviewAnswerEntity>> answers = ArgumentCaptor.forClass(List.class);
        verify(answerRepository).saveAll(answers.capture());
        assertThat(answers.getValue()).hasSize(1);
        assertThat(answers.getValue().get(0).getQuestionText()).isEqualTo("追问一");
    }

    @Test
    @DisplayName("详情接口对旧数据中的重复问答进行防御性去重")
    void shouldDeduplicateLegacyAnswersInDetail() {
        InterviewSessionEntity session = session("session-duplicate");
        InterviewAnswerEntity first = answer(1L, "session-duplicate", 0, "问题一", "回答一", false);
        InterviewAnswerEntity duplicate = answer(2L, "session-duplicate", 0, "问题一", "回答一", false);
        when(persistenceService.getById("session-duplicate")).thenReturn(session);
        when(answerRepository.findBySessionIdOrderById("session-duplicate"))
                .thenReturn(List.of(first, duplicate));

        var result = service.getSession("session-duplicate", 7L);

        assertThat(result.answers()).containsExactly(first);
    }

    @Test
    @DisplayName("模型判断无需追问时直接进入下一道主问题")
    void shouldMoveToNextMainQuestionWhenFollowUpIsUnnecessary() throws Exception {
        InterviewSessionEntity session = session("session-2");
        var runtimeQa = List.of(
                new InterviewSessionService.QAItem("自我介绍", "我的技术栈和项目经历", false, 0, 0),
                new InterviewSessionService.QAItem("项目中的登录流程如何实现？", null, false, 1, 0));
        var baseQuestions = new InterviewQuestionResult(
                java.util.stream.IntStream.range(0, 8)
                        .mapToObj(i -> new InterviewQuestionResult.InterviewQuestion("主问题 " + i, List.of()))
                        .toList());
        InterviewQuestionEntity cached = new InterviewQuestionEntity();
        cached.setSessionId("session-2");
        cached.setQuestionsJson(objectMapper.writeValueAsString(baseQuestions));

        when(persistenceService.getById("session-2")).thenReturn(session);
        when(redisService.get("interview:qa:session-2"))
                .thenReturn(Optional.of(objectMapper.writeValueAsString(runtimeQa)));
        when(redisService.get("interview:finished:session-2")).thenReturn(Optional.of("false"));
        when(questionRepository.findById("session-2")).thenReturn(Optional.of(cached));
        when(followupService.decideFollowUp(
                any(), anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt(), any()))
                .thenReturn(FollowupGeneratorService.FollowUpDecision.next("回答已经完整"));
        when(answerRepository.findBySessionIdOrderById("session-2")).thenReturn(List.of());

        var result = service.submitAnswer("session-2", 1, "先校验参数，再查询用户并签发 JWT。", 7L);

        assertThat(result.status()).isEqualTo(InterviewStatus.IN_PROGRESS);
        assertThat(session.getCurrentQuestionIndex()).isEqualTo(2);
        ArgumentCaptor<String> qaJson = ArgumentCaptor.forClass(String.class);
        verify(redisService).set(
                org.mockito.ArgumentMatchers.eq("interview:qa:session-2"),
                qaJson.capture(),
                org.mockito.ArgumentMatchers.eq(Duration.ofHours(24)));
        @SuppressWarnings("unchecked")
        List<InterviewSessionService.QAItem> savedQa = objectMapper.readValue(
                qaJson.getValue(),
                objectMapper.getTypeFactory().constructCollectionType(
                        List.class, InterviewSessionService.QAItem.class));
        assertThat(savedQa.get(savedQa.size() - 1).question()).isEqualTo("主问题 2");
        assertThat(savedQa.get(savedQa.size() - 1).followUp()).isFalse();
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

    private InterviewAnswerEntity answer(Long id, String sessionId, int questionIndex,
                                         String question, String answer, boolean followUp) {
        InterviewAnswerEntity entity = new InterviewAnswerEntity();
        entity.setId(id);
        entity.setSessionId(sessionId);
        entity.setQuestionIndex(questionIndex);
        entity.setQuestionText(question);
        entity.setAnswerText(answer);
        entity.setIsFollowUp(followUp);
        return entity;
    }
}
