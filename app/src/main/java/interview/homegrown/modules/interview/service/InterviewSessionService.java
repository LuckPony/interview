package interview.homegrown.modules.interview.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.homegrown.common.exception.BusinessException;
import interview.homegrown.common.exception.ErrorCode;
import interview.homegrown.infrastructure.redis.RedisService;
import interview.homegrown.modules.interview.model.*;
import interview.homegrown.modules.interview.repository.InterviewAnswerRepository;
import interview.homegrown.modules.interview.repository.InterviewQuestionRepository;
import interview.homegrown.modules.interview.repository.InterviewSessionRepository;
import interview.homegrown.modules.resume.model.ResumeEntity;
import interview.homegrown.modules.resume.repository.ResumeRepository;
import interview.homegrown.modules.drill.repository.ConceptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 面试会话服务 —— 面试业务核心（动态追问版）
 *
 * <p>流程：创建会话时按难度预出 {@link DifficultyConfig#BASE_QUESTION_COUNT} 道基础题
 * （第 1 题固定自我介绍，不追问）；答题过程中<b>根据用户回答动态生成追问</b>，
 * 追问数量与深度由难度决定；全部答完或<b>超时（难度时长）</b>后进入待评估，
 * 届时把本轮问答一次性落库，用户点击评估后生成总分与逐题反馈。</p>
 *
 * <p>运行时数据存于 Redis（进程内缓存）：问答流 interview:qa:{sessionId}、完成标记 interview:finished:{sessionId}。</p>
 */
@Service
public class InterviewSessionService {

    private static final Logger log = LoggerFactory.getLogger(InterviewSessionService.class);

    private static final String QA_KEY = "interview:qa:";
    private static final String FINISHED_KEY = "interview:finished:";

    private final ResumeRepository resumeRepository;
    private final InterviewPersistenceService persistenceService;
    private final InterviewQuestionService questionService;
    private final FollowupGeneratorService followupService;
    private final InterviewEvaluateService evaluateService;
    private final InterviewSkillService skillService;
    private final InterviewSessionRepository sessionRepository;
    private final InterviewAnswerRepository answerRepository;
    private final ConceptRepository conceptRepo;
    private final InterviewQuestionRepository questionRepo;
    private final RedisService redisService;
    private final ObjectMapper objectMapper;

    public InterviewSessionService(ResumeRepository resumeRepository,
                                   InterviewSkillService skillService,
                                   InterviewSessionRepository sessionRepository,
                                   InterviewPersistenceService persistenceService,
                                   InterviewQuestionService questionService,
                                   FollowupGeneratorService followupService,
                                   InterviewEvaluateService evaluateService,
                                   InterviewAnswerRepository answerRepository,
                                   ConceptRepository conceptRepo,
                                   InterviewQuestionRepository questionRepo,
                                   RedisService redisService,
                                   ObjectMapper objectMapper) {
        this.resumeRepository = resumeRepository;
        this.persistenceService = persistenceService;
        this.questionService = questionService;
        this.followupService = followupService;
        this.evaluateService = evaluateService;
        this.skillService = skillService;
        this.sessionRepository = sessionRepository;
        this.answerRepository = answerRepository;
        this.conceptRepo = conceptRepo;
        this.questionRepo = questionRepo;
        this.redisService = redisService;
        this.objectMapper = objectMapper;
    }

    //===================== 创建会话 ===================

    public InterviewSessionDTO createSession(CreateSessionRequest request, Long userId) {
        boolean hasResume = request.resumeId() != null;
        boolean hasPlans = request.planIds() != null && !request.planIds().isEmpty();
        if (!hasResume && !hasPlans) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请上传简历或选择至少一个学习方向，才能开始面试");
        }

        InterviewDifficulty difficulty = request.difficulty() != null ? request.difficulty() : InterviewDifficulty.MIDDLE;
        DifficultyConfig cfg = DifficultyConfig.of(difficulty);

        // 简历文本（校验归属）
        Long resumeId = request.resumeId();
        String resumeText = "";
        if (resumeId != null) {
            ResumeEntity resumeEntity = resumeRepository.findById(resumeId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_NOT_FOUND, "简历不存在"));
            if (!userId.equals(resumeEntity.getUserId())) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "无权使用该简历");
            }
            resumeText = resumeEntity.getResumeText() == null ? "" : resumeEntity.getResumeText();
        }

        // 学习方向知识点（可选，多选合并）
        List<String> planConcepts = (request.planIds() == null || request.planIds().isEmpty())
                ? List.of()
                : request.planIds().stream()
                        .distinct()
                        .flatMap(pid -> conceptRepo.findByStudyPlanId(pid).stream())
                        .map(c -> c.getTopic() + "/" + c.getName())
                        .distinct()
                        .limit(200)
                        .toList();

        String skillName = (request.skillId() != null && !request.skillId().isBlank())
                ? skillService.getSkill(request.skillId()).getName()
                : "";

        // 出 6 道基础题（第 1 题自我介绍固定，追问动态生成）
        InterviewQuestionResult baseQuestions = questionService.generateBaseQuestions(
                skillName, difficulty, resumeText, planConcepts, hasResume && hasPlans, request.llmProvider());

        // 创建会话实体并落库
        InterviewSessionEntity session = new InterviewSessionEntity();
        session.setId(UUID.randomUUID().toString());
        session.setUserId(userId);
        session.setResumeId(resumeId);
        session.setSkillId(request.skillId());
        session.setDifficulty(difficulty);
        session.setStatus(InterviewStatus.IN_PROGRESS);
        session.setTotalQuestions(baseQuestions.questions().size());
        session.setCurrentQuestionIndex(0);
        session.setLlmProvider(request.llmProvider());
        session.setMode(request.mode() != null && !request.mode().isBlank() ? request.mode().toUpperCase() : "TEXT");
        session.setPlanIds(hasPlans
                ? request.planIds().stream().map(String::valueOf).distinct().collect(Collectors.joining(","))
                : null);
        session.setStartAt(LocalDateTime.now());
        session.setDurationMin(DifficultyConfig.UNIFIED_DURATION_MINUTES);
        persistenceService.save(session);

        // 题目持久化到数据库（进程重启可恢复）
        cacheQuestions(session.getId(), baseQuestions);

        // 初始化运行时：问答流 = [自我介绍题（未答）]
        List<QAItem> qa = new ArrayList<>();
        qa.add(new QAItem(baseQuestions.questions().get(0).question(), null, false, 0));
        saveQa(session.getId(), qa);
        redisService.set(FINISHED_KEY + session.getId(), "false");

        log.info("面试会话创建成功: sessionId={}, 难度={}, 基础题数={}, 时长约{}分钟",
                session.getId(), difficulty, baseQuestions.questions().size(), cfg.durationText());
        return toDetailDTO(session);
    }

    //===================== 取当前题目 ===================

    public CurrentQuestion getCurrentQuestion(String sessionId, Long userId) {
        InterviewSessionEntity session = requireOwned(sessionId, userId);

        if (session.getStatus() == InterviewStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.INTERVIEW_ALREADY_COMPLETED, "该场面试已完成评估");
        }

        boolean finished = isFinished(sessionId);
        List<QAItem> qa = readQa(sessionId);
        DifficultyConfig cfg = DifficultyConfig.of(session.getDifficulty());
        long remaining = remainingSeconds(session);

        // 完成/超时（且当前问题已答）→ 待评估
        if (finished) {
            return new CurrentQuestion(sessionId, session.getTotalQuestions(),
                    0, 0, cfg.followUpCount(), true, Math.max(0, remaining), null, toHistory(qa));
        }

        // 当前要答的问题 = 问答流中最后一个未答的问题
        QAItem current = lastUnanswered(qa);
        if (current == null) {
            // 没有未答问题（理论上不会到这，兜底）
            return new CurrentQuestion(sessionId, session.getTotalQuestions(),
                    0, 0, cfg.followUpCount(), true, Math.max(0, remaining), null, toHistory(qa));
        }

        int followUpIndex = current.followUp() ? current.fuIndex() : 0;
        return new CurrentQuestion(sessionId, session.getTotalQuestions(),
                current.baseIndex(), followUpIndex, cfg.followUpCount(), false,
                Math.max(0, remaining), current.question(), toHistory(qa));
    }

    //===================== 提交答案 ===================

    public InterviewSessionDTO submitAnswer(String sessionId, int questionIndex, String answerText, Long userId) {
        InterviewSessionEntity session = requireOwned(sessionId, userId);

        if (session.getStatus() != InterviewStatus.IN_PROGRESS) {
            throw new BusinessException(ErrorCode.INTERVIEW_ALREADY_COMPLETED, "当前会话状态: " + session.getStatus());
        }

        List<QAItem> qa = readQa(sessionId);
        DifficultyConfig cfg = DifficultyConfig.of(session.getDifficulty());
        InterviewQuestionResult baseQuestions = readCachedQuestion(sessionId);
        List<String> baseTexts = baseQuestions.questions().stream()
                .map(InterviewQuestionResult.InterviewQuestion::question)
                .toList();

        // 1. 给当前未答问题补答案
        boolean answered = false;
        List<QAItem> updated = new ArrayList<>();
        for (QAItem item : qa) {
            if (item.answer() == null && !answered) {
                updated.add(new QAItem(item.question(), answerText, item.followUp(), item.baseIndex(), item.fuIndex()));
                answered = true;
            } else {
                updated.add(item);
            }
        }
        if (!answered) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "当前没有待回答的问题");
        }
        qa = updated;

        // 2. 判断是否超时 → 超时则结束（答完当前问题即止）
        boolean timeout = isTimeout(session);
        boolean allDone = allBaseDone(qa, baseTexts.size());

        if (timeout || allDone) {
            // 批量落库 + 进入待评估
            persistQa(session.getId(), qa);
            redisService.set(FINISHED_KEY + session.getId(), "true");
            session.setStatus(InterviewStatus.PENDING_EVALUATION);
            persistenceService.save(session);
            log.info("面试答题结束: sessionId={}, 原因={}", sessionId, timeout ? "超时" : "全部答完");
            return toDetailDTO(session);
        }

        // 3. 生成下一个问题（追问 或 下一道基础题）
        QAItem last = qa.get(qa.size() - 1);
        String skillName = skillNameOf(session);
        String nextQuestion;
        boolean nextIsFollowUp;
        int nextBaseIndex = last.baseIndex();
        int nextFuIndex = 0;

        if (!last.followUp() && last.baseIndex() == 0) {
            // 自我介绍答完 → 进入第 2 道基础题（不追问）
            nextBaseIndex = 1;
            nextQuestion = baseTexts.get(1);
            nextIsFollowUp = false;
        } else if (!last.followUp()) {
            // 基础题答完 → 生成第 1 个追问；若回答表示“不清楚/不会”则改为最基础的概念确认问题
            if (cfg.followUpCount() > 0) {
                nextQuestion = followupService.generateFollowUp(
                        session.getDifficulty(), skillName,
                        baseTexts.get(last.baseIndex()), last.answer(),
                        0, cfg.followUpCount(), session.getLlmProvider(),
                        looksIgnorant(last.answer()));
                nextIsFollowUp = true;
                nextFuIndex = 1;
            } else {
                nextBaseIndex = last.baseIndex() + 1;
                nextQuestion = nextBaseIndex < baseTexts.size() ? baseTexts.get(nextBaseIndex) : null;
                nextIsFollowUp = false;
            }
        } else {
            // 追问答完：仍表示“不清楚/不会”（基础问题也答不上来）→ 停止追问，直接进入下一道基础题
            if (looksIgnorant(last.answer())) {
                log.info("候选人基础概念也未答上，停止追问并进入下一题: sessionId={}, baseIndex={}", sessionId, last.baseIndex());
                nextBaseIndex = last.baseIndex() + 1;
                nextQuestion = nextBaseIndex < baseTexts.size() ? baseTexts.get(nextBaseIndex) : null;
                nextIsFollowUp = false;
            } else if (last.fuIndex() < cfg.followUpCount()) {
                // 答上来了 → 继续延伸追问
                nextQuestion = followupService.generateFollowUp(
                        session.getDifficulty(), skillName,
                        baseTexts.get(last.baseIndex()), last.answer(),
                        last.fuIndex(), cfg.followUpCount(), session.getLlmProvider());
                nextIsFollowUp = true;
                nextFuIndex = last.fuIndex() + 1;
            } else {
                nextBaseIndex = last.baseIndex() + 1;
                nextQuestion = nextBaseIndex < baseTexts.size() ? baseTexts.get(nextBaseIndex) : null;
                nextIsFollowUp = false;
            }
        }

        if (nextQuestion == null) {
            // 没有下一题（全部基础题答完）→ 待评估
            persistQa(session.getId(), qa);
            redisService.set(FINISHED_KEY + session.getId(), "true");
            session.setStatus(InterviewStatus.PENDING_EVALUATION);
            persistenceService.save(session);
            return toDetailDTO(session);
        }

        qa.add(new QAItem(nextQuestion, null, nextIsFollowUp, nextBaseIndex, nextFuIndex));
        saveQa(session.getId(), qa);
        session.setCurrentQuestionIndex(nextBaseIndex);
        persistenceService.save(session);

        log.info("答案已提交并推进: sessionId={}, baseIndex={}, fuIndex={}, isFollowUp={}",
                sessionId, nextBaseIndex, nextFuIndex, nextIsFollowUp);
        return toDetailDTO(session);
    }

    //================== 完成面试并评估 ==================

    public InterviewSessionDTO completeInterview(String sessionId, Long userId) {
        InterviewSessionEntity session = requireOwned(sessionId, userId);

        if (session.getStatus() == InterviewStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.INTERVIEW_ALREADY_COMPLETED, "该场面试已完成评估");
        }

        // 若 Redis 运行时还在且未落库，先落库
        List<QAItem> qa = readQa(sessionId);
        if (!qa.isEmpty()) {
            persistQa(sessionId, qa);
        }

        // 从数据库读取本轮全部问答（按时间顺序）
        List<InterviewAnswerEntity> answers = answerRepository.findBySessionIdOrderById(sessionId);
        if (answers.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "本场面试还没有作答记录");
        }

        List<String> questions = answers.stream().map(InterviewAnswerEntity::getQuestionText).toList();
        List<String> answerTexts = answers.stream()
                .map(a -> a.getAnswerText() == null ? "" : a.getAnswerText())
                .toList();

        // LLM 评估
        String skillName = skillNameOf(session);
        InterviewEvaluationResult evaluation = evaluateService.evaluate(
                sessionId, questions, answerTexts, skillName, session.getLlmProvider());

        // 回填逐题评分
        for (int i = 0; i < answers.size(); i++) {
            InterviewAnswerEntity a = answers.get(i);
            if (i < evaluation.questionEvaluations().size()) {
                var qe = evaluation.questionEvaluations().get(i);
                a.setScore(qe.score());
                a.setFeedback(qe.feedback());
            }
            answerRepository.save(a);
        }

        session.setTotalScore(evaluation.totalScore());
        session.setStatus(InterviewStatus.COMPLETED);
        try {
            session.setEvaluationJson(objectMapper.writeValueAsString(evaluation));
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "评估 JSON 序列化失败");
        }
        persistenceService.save(session);

        // 清理运行时与题目记录
        redisService.delete(QA_KEY + sessionId);
        redisService.delete(FINISHED_KEY + sessionId);
        questionRepo.deleteBySessionId(sessionId);

        log.info("面试完成并评估: sessionId={}, 总分={}", sessionId, evaluation.totalScore());
        return toDetailDTO(session);
    }

    //====================== 查询 ==================

    public List<InterviewListItemDTO> listSessions(Long userId) {
        return sessionRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(s -> {
                    int answered = answerRepository.findBySessionIdOrderById(s.getId()).size();
                    return new InterviewListItemDTO(
                            s.getId(), s.getSkillId(), skillNameOf(s), s.getDifficulty(), s.getStatus(),
                            s.getTotalQuestions(), answered, s.getTotalScore(), s.getCreatedAt(),
                            s.getMode() != null ? s.getMode() : "TEXT");
                })
                .toList();
    }

    public InterviewSessionDTO getSession(String sessionId, Long userId) {
        return toDetailDTO(requireOwned(sessionId, userId));
    }

    //===================== 私有方法 =====================

    private InterviewSessionEntity requireOwned(String sessionId, Long userId) {
        InterviewSessionEntity session = persistenceService.getById(sessionId);
        if (!userId.equals(session.getUserId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该面试会话");
        }
        return session;
    }

    /** 判断候选人回答是否表示“不清楚/不会”（回答过短也算没答上来） */
    private boolean looksIgnorant(String answer) {
        if (answer == null || answer.isBlank()) return true;
        String t = answer.trim();
        if (t.length() < 8) return true; // 太短 = 没答上来
        String lower = t.toLowerCase();
        String[] hints = {"不知道", "不清楚", "不会", "没学过", "没接触", "没了解", "不懂", "没做过",
                "没听过", "忘了", "不了解", "讲不上", "说不出", "没深入"};
        for (String h : hints) {
            if (lower.contains(h)) return true;
        }
        return false;
    }

    /** 问答流中的一条记录：question 已生成、answer 为 null 表示待回答 */
    public record QAItem(String question, String answer, boolean followUp, int baseIndex, int fuIndex) {
        public QAItem(String question, String answer, boolean followUp, int baseIndex) {
            this(question, answer, followUp, baseIndex, 0);
        }
    }

    private void saveQa(String sessionId, List<QAItem> qa) {
        try {
            redisService.set(QA_KEY + sessionId, objectMapper.writeValueAsString(qa), Duration.ofHours(24));
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "问答流序列化失败");
        }
    }

    private List<QAItem> readQa(String sessionId) {
        return redisService.get(QA_KEY + sessionId)
                .map(json -> {
                    try {
                        return objectMapper.readValue(json, new TypeReference<List<QAItem>>() {});
                    } catch (Exception e) {
                        throw new BusinessException(ErrorCode.INTERNAL_ERROR, "问答流解析失败");
                    }
                })
                .orElseGet(ArrayList::new);
    }

    private boolean isFinished(String sessionId) {
        return "true".equals(redisService.get(FINISHED_KEY + sessionId).orElse("false"));
    }

    private long remainingSeconds(InterviewSessionEntity session) {
        if (session.getStartAt() == null || session.getDurationMin() == null) return 0;
        long deadline = session.getStartAt().plusMinutes(session.getDurationMin())
                .atZone(java.time.ZoneId.systemDefault()).toInstant().getEpochSecond();
        return deadline - System.currentTimeMillis() / 1000;
    }

    private boolean isTimeout(InterviewSessionEntity session) {
        return remainingSeconds(session) <= 0;
    }

    private QAItem lastUnanswered(List<QAItem> qa) {
        for (int i = qa.size() - 1; i >= 0; i--) {
            if (qa.get(i).answer() == null) return qa.get(i);
        }
        return null;
    }

    private boolean allBaseDone(List<QAItem> qa, int baseSize) {
        return qa.stream().anyMatch(i -> i.baseIndex() >= baseSize - 1)
                && qa.get(qa.size() - 1).answer() != null
                && !qa.get(qa.size() - 1).followUp();
    }

    private List<QaHistory> toHistory(List<QAItem> qa) {
        return qa.stream().map(i -> new QaHistory(i.question(), i.answer(), i.followUp())).toList();
    }

    /** 已答问答（前端对话线展示用） */
    public record QaHistory(String question, String answer, boolean followUp) {}

    /** 把问答流一次性落库（面试结束进入待评估时调用） */
    private void persistQa(String sessionId, List<QAItem> qa) {
        for (QAItem item : qa) {
            if (item.answer() == null) continue; // 未答的当前题不落库
            InterviewAnswerEntity answer = new InterviewAnswerEntity();
            answer.setSessionId(sessionId);
            answer.setQuestionIndex(item.baseIndex());
            answer.setQuestionText(item.question());
            answer.setAnswerText(item.answer());
            answer.setIsFollowUp(item.followUp());
            answerRepository.save(answer);
        }
        log.info("问答已落库: sessionId={}, 条数={}", sessionId, qa.size());
    }

    private void cacheQuestions(String sessionId, InterviewQuestionResult questions) {
        try {
            InterviewQuestionEntity entity = new InterviewQuestionEntity();
            entity.setSessionId(sessionId);
            entity.setQuestionsJson(objectMapper.writeValueAsString(questions));
            questionRepo.save(entity);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "题目持久化失败");
        }
    }

    private InterviewQuestionResult readCachedQuestion(String sessionId) {
        return questionRepo.findById(sessionId)
                .map(InterviewQuestionEntity::getQuestionsJson)
                .map(json -> {
                    try {
                        return objectMapper.readValue(json, new TypeReference<InterviewQuestionResult>() {});
                    } catch (JsonProcessingException e) {
                        throw new BusinessException(ErrorCode.INTERNAL_ERROR, "题目解析失败");
                    }
                })
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INTERVIEW_SESSION_NOT_FOUND, "该会话的面试题目不存在，可能已被清理"));
    }

    private InterviewSessionDTO toDetailDTO(InterviewSessionEntity s) {
        List<InterviewAnswerEntity> answers = answerRepository.findBySessionIdOrderById(s.getId());
        return new InterviewSessionDTO(
                s.getId(), s.getSkillId(), skillNameOf(s), s.getDifficulty(), s.getStatus(),
                s.getTotalQuestions(), s.getCurrentQuestionIndex(), s.getTotalScore(),
                s.getLlmProvider(), s.getCreatedAt(), answers,
                s.getMode() != null ? s.getMode() : "TEXT",
                s.getPlanIds(),
                parseEvaluation(s.getEvaluationJson()),
                s.getDurationMin(),
                Math.max(0, remainingSeconds(s)));
    }

    private InterviewEvaluationResult parseEvaluation(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, InterviewEvaluationResult.class);
        } catch (Exception e) {
            log.warn("评估 JSON 解析失败（忽略）: {}", e.getMessage());
            return null;
        }
    }

    private String skillNameOf(InterviewSessionEntity s) {
        if (s.getSkillId() != null && !s.getSkillId().isBlank()) {
            try {
                return skillService.getSkill(s.getSkillId()).getName();
            } catch (Exception ignored) {
            }
        }
        return "综合面试";
    }

    //=============== 内部 Record =================

    public record CurrentQuestion(
            String sessionId,
            int totalQuestions,
            int baseIndex,
            int followUpIndex,
            int totalFollowUps,
            boolean finished,
            long remainingSeconds,
            String question,
            List<QaHistory> history
    ) {
    }
}
