package interview.homegrown.modules.interview.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.homegrown.common.exception.BusinessException;
import interview.homegrown.common.exception.ErrorCode;
import interview.homegrown.infrastructure.redis.RedisService;
import interview.homegrown.modules.interview.config.InterviewSkillProperties;
import interview.homegrown.modules.interview.model.*;
import interview.homegrown.modules.interview.repository.InterviewAnswerRepository;
import interview.homegrown.modules.interview.repository.InterviewSessionRepository;
import interview.homegrown.modules.resume.model.ResumeEntity;
import interview.homegrown.modules.resume.repository.ResumeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 面试会话服务 —— 面试业务核心

 * 职责：
 * 1. 创建会话：生成题目（LLM）→ 题目缓存 Redis → 落库
 * 2. 取当前题：从 Redis 读题目列表，按 currentQuestionIndex 定位
 * 3. 提交答案：答案落库，推进 currentQuestionIndex
 * 4. 完成面试：触发评估，更新状态与分数

 * 题目存储策略：题目列表存在 Redis（key=interview:questions:{sessionId}），
 * 面试期间可恢复；面试结束后删除缓存。
 */

@Service
public class InterviewSessionService {

    private static final Logger log = LoggerFactory.getLogger(InterviewSessionService.class);

    private static final String QUESTION_CACHE_KEY = "interview:question";
    private static final Duration CACHE_TIL = Duration.ofHours(24);

    private final ResumeRepository resumeRepository;
    private final InterviewPersistenceService persistenceService;
    private final InterviewQuestionService questionService;
    private final InterviewEvaluateService evaluateService;
    private final InterviewSkillService skillService;
    private final InterviewSessionRepository sessionRepository;
    private final InterviewAnswerRepository answerRepository;
    private final ObjectMapper objectMapper;
    private final RedisService redisService;

    public InterviewSessionService(ResumeRepository resumeRepository,
                                   InterviewSkillService skillService,InterviewSessionRepository sessionRepository,
                                   InterviewPersistenceService persistenceService,InterviewQuestionService questionService,
                                   InterviewEvaluateService evaluateService,InterviewAnswerRepository answerRepository,
                                   ObjectMapper objectMapper,RedisService redisService) {

        this.resumeRepository = resumeRepository;
        this.persistenceService = persistenceService;
        this.questionService = questionService;
        this.evaluateService = evaluateService;
        this.skillService = skillService;
        this.sessionRepository = sessionRepository;
        this.answerRepository = answerRepository;
        this.objectMapper = objectMapper;
        this.redisService = redisService;
    }

    //=====================创建会话===================

    public InterviewSessionDTO createSession(CreateSessionRequest request){

        //校验skill
        InterviewSkillProperties.SkillConfig skill = skillService.getSkill(request.skillId());

        //确定题目数量
        int questionCount = request.questionCount() !=null ? request.questionCount() : skill.getDefaultQuestionCount();

        //确定难度
        InterviewDifficulty difficulty = request.difficulty() !=null ? request.difficulty() : InterviewDifficulty.MIDDLE;

        //关联简历文本
        Long resumeId = request.resumeId();
        String resumeText = resumeRepository.findById(resumeId)
                .map(ResumeEntity::getResumeText)
                .orElse("");

        //LLM出题
        InterviewQuestionResult questionResult = questionService.generateQueations(request.skillId(),difficulty,questionCount,
                                                                                     resumeText,request.llmProvider()   );

        //创建会话实体并落库
        InterviewSessionEntity session = new InterviewSessionEntity();
        session.setId(UUID.randomUUID().toString());
        session.setResumeId(resumeId);
        session.setSkillId(request.skillId());
        session.setDifficulty(difficulty);
        session.setStatus(InterviewStatus.IN_PROGRESS);
        session.setTotalQuestions(questionResult.questions().size());//这里之所以不用questionCount是因为不能百分百相信Ai，以实际为主
        session.setCurrentQuestionIndex(0);
        session.setLlmProvider(request.llmProvider());
        persistenceService.save(session);

        //题目列表缓存到Redis
        cacheQuestions(session.getId(),questionResult);

        log.info("面试会话创建成功: sessionId={}, skillId={}, 题目数={}",
                session.getId(), request.skillId(), questionResult.questions().size());
        return toDetailDTO(session);
    }

    //================取当前题目==============

    public CurrentQuestion getCurrentQuestion(String sessionId){

        InterviewSessionEntity session = persistenceService.getById(sessionId);

        if(session.getStatus() != InterviewStatus.IN_PROGRESS){
            throw new BusinessException(ErrorCode.INTERVIEW_ALREADY_COMPLETED, "当前会话状态: " + session.getStatus());
        }

        InterviewQuestionResult questions = readCachedQuestion(sessionId);
        int index = session.getCurrentQuestionIndex();

        if(index >= questions.questions().size()){
            throw new BusinessException(ErrorCode.BAD_REQUEST, "所有题目已答完，请完成面试");
        }

        InterviewQuestionResult.InterviewQuestion q = questions.questions().get(index);
        return new CurrentQuestion(
                sessionId,
                index,
                session.getTotalQuestions(),
                q.question(),
                q.followups()
        );
    }

    //====================提交答案======================

    public void submitAnswer(String sessionId, int questionIndex, String answerText){

        InterviewSessionEntity session = persistenceService.getById(sessionId);

        if(session.getStatus() != InterviewStatus.IN_PROGRESS){
            throw new BusinessException(ErrorCode.INTERVIEW_ALREADY_COMPLETED, "当前会话状态: " + session.getStatus());
        }

        InterviewQuestionResult questions = readCachedQuestion(sessionId);
        if(questionIndex >= questions.questions().size()){
            throw new BusinessException(ErrorCode.BAD_REQUEST, "题目索引越界");
        }

        InterviewQuestionResult.InterviewQuestion q = questions.questions().get(questionIndex);

        //保存主问题答案（追加问题后面再加）
        InterviewAnswerEntity answer = new InterviewAnswerEntity();
        answer.setSessionId(sessionId);
        answer.setQuestionIndex(questionIndex);
        answer.setQuestionText(q.question());
        answer.setAnswerText(answerText);
        answer.setIsFollowUp(false);
        answerRepository.save(answer);

        //更新当前处理问题的索引
        int nextIndex = Math.min(questionIndex+1, questions.questions().size());
        session.setCurrentQuestionIndex(nextIndex);
        persistenceService.save(session);

        log.info("答案已提交: sessionId={}, questionIndex={}", sessionId, questionIndex);
    }

    //==================完成面试 并 进行评估=================

    public InterviewSessionDTO completeInterview(String sessionId){

        InterviewSessionEntity session = persistenceService.getById(sessionId);

        if(session.getStatus() != InterviewStatus.IN_PROGRESS){
            throw new BusinessException(ErrorCode.INTERVIEW_ALREADY_COMPLETED, "当前会话状态: " + session.getStatus());
        }

        //取保存的所有答案
        List<InterviewAnswerEntity> answers = answerRepository.findBySessionIdOrderByQuestionIndex(sessionId);

        //从缓存取题目
        InterviewQuestionResult questions = readCachedQuestion(sessionId);
        List<String> questionText = questions.questions().stream()
                .map(InterviewQuestionResult.InterviewQuestion::question)
                .toList();

        //先把答案列表转换为Map，Key设置为questionIndex
        Map<Integer, String> answerMap = answers.stream()
                .collect(Collectors.toMap(
                        InterviewAnswerEntity::getQuestionIndex,
                        InterviewAnswerEntity::getAnswerText,
                        (v1,v2) -> v1
                ));
        //按照question的序号
        List<String> answerTexts = java.util.stream.IntStream.range(0,questions.questions().size())
                .mapToObj(index -> answerMap.getOrDefault(index,""))
                .toList();

        //调用 LLM 评估
        String skillName = skillService.getSkill(session.getSkillId()).getName();
        InterviewEvaluationResult evaluation = evaluateService.evaluate(
                sessionId,questionText,answerTexts,skillName,session.getLlmProvider()
        );

        //逐题回填 score/feedback 到答案表
        for (int i = 0;i<answers.size();i++){

            InterviewAnswerEntity answer = answers.get(i);

            if(i < evaluation.questionEvaluations().size()){
                var qe = evaluation.questionEvaluations().get(i);
                answer.setScore(qe.score());
                answer.setFeedback(qe.feedback());
            }

            answerRepository.save(answer);
        }

        //更新会话：总分、评估JSON、状态
        session.setTotalScore(evaluation.totalScore());
        session.setStatus(InterviewStatus.COMPLETED);

        try{
            session.setEvaluationJson(objectMapper.writeValueAsString(evaluation));
        }catch(JsonProcessingException e){
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,"LLM评估 JSON 序列化失败");
        }

        persistenceService.save(session);

        //清理题目缓存
        redisService.delete(QUESTION_CACHE_KEY + sessionId);

        log.info("面试完成并评估: sessionId={}, 总分={}", sessionId, evaluation.totalScore());

        return toDetailDTO(session);
    }



    //====================私有方法=====================

    private void cacheQuestions(String sessionId,InterviewQuestionResult questions){

        try{
            String json = objectMapper.writeValueAsString(questions);   //增加可读性，方便调试
            redisService.set(QUESTION_CACHE_KEY + sessionId,json,CACHE_TIL);
        }catch (JsonProcessingException e){
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,"题目缓存序列化失败");
        }
    }

    private InterviewSessionDTO toDetailDTO(InterviewSessionEntity s){

        List<InterviewAnswerEntity> answers = answerRepository.findBySessionIdOrderByQuestionIndex(s.getId());
        return new InterviewSessionDTO(
                s.getId(),
                s.getSkillId(),
                skillService.getSkill(s.getSkillId()).getName(),
                s.getDifficulty(),
                s.getStatus(),
                s.getTotalQuestions(),
                s.getCurrentQuestionIndex(),
                s.getTotalScore(),
                s.getLlmProvider(),
                s.getCreatedAt(),
                answers
        );
    }

    private InterviewQuestionResult readCachedQuestion(String sessionId){

        return redisService.get(QUESTION_CACHE_KEY + sessionId)
                .map(json -> {
                    try{
                        return objectMapper.readValue(json, new TypeReference<InterviewQuestionResult>() {
                        });
                    } catch (JsonProcessingException e) {
                        throw new BusinessException(ErrorCode.INTERNAL_ERROR, "题目缓存反序列化失败");
                    }
                })
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INTERVIEW_SESSION_NOT_FOUND, "题目缓存不存在或已过期"));
    }

    //===============内部Record=================

    public record CurrentQuestion(
            String sessionId,
            int currentIndex,
            int totalQuestions,
            String question,
            List<String> followUps
    ){

    }
}
