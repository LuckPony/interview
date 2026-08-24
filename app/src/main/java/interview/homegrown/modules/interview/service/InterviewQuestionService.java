package interview.homegrown.modules.interview.service;

import interview.homegrown.common.ai.StructuredOutputInvoker;
import interview.homegrown.modules.interview.model.DifficultyConfig;
import interview.homegrown.modules.interview.model.InterviewDifficulty;
import interview.homegrown.modules.interview.model.InterviewQuestionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 面试出题服务
 * 依据 = 简历（70% 权重）+ 学习方向知识点（30% 权重），可只选其一。
 *
 * <p>重构后：所有难度都预出 {@link DifficultyConfig#BASE_QUESTION_COUNT} 道<b>基础题</b>，
 * 第 1 题固定为「请介绍一下你自己」（不追问）；其余基础题结合简历/学习方向由 LLM 生成，
 * 难度与时长匹配（初级浅、中级中、高级深）。追问不再预生成，改为回答后动态生成。</p>
 */
@Service
public class InterviewQuestionService {

    private static final Logger log = LoggerFactory.getLogger(InterviewQuestionService.class);

    private static final String SELF_INTRO = "请先简单介绍一下你自己，包括你的技术栈和项目经历。";

    private final StructuredOutputInvoker invoker;
    private final InterviewSkillService skillService;

    public InterviewQuestionService(StructuredOutputInvoker invoker, InterviewSkillService skillService) {
        this.invoker = invoker;
        this.skillService = skillService;
    }

    private static final String SYSTEM_PROMPT = """
            你是一位资深的技术面试官，擅长针对不同技术方向设计高质量的面试题。
            请输出 JSON 格式的题目列表，每个题目只包含 question 字段（主问题），followups 返回空数组。
            """;

    /**
     * 生成面试基础题（数量 = DifficultyConfig.BASE_QUESTION_COUNT）
     *
     * @param skillName      面试方向名称（可空：方向由简历/学习方向决定）
     * @param difficulty     难度（决定题目深浅与面试时长）
     * @param resumeText     简历文本（可空；非空时权重 70%）
     * @param planConcepts   学习方向知识点（可空；非空时权重 30%）
     * @param mixed          简历与学习方向是否都提供（都提供时提示 LLM 按 70/30 占比）
     * @param provider       LLM Provider
     */
    public InterviewQuestionResult generateBaseQuestions(String skillName,
                                                         InterviewDifficulty difficulty,
                                                         String resumeText,
                                                         List<String> planConcepts,
                                                         boolean mixed,
                                                         String provider) {

        int total = DifficultyConfig.BASE_QUESTION_COUNT;
        int toGenerate = total - 1; // 第 1 题自我介绍由代码固定
        DifficultyConfig cfg = DifficultyConfig.of(difficulty);

        String direction = (skillName != null && !skillName.isBlank())
                ? skillName
                : (planConcepts != null && !planConcepts.isEmpty() ? "学习方向知识点" : "候选人简历相关技术栈");

        String difficultyText = switch (difficulty) {
            case JUNIOR -> "初级：考察基础概念掌握与简单应用；题目较浅，整场约 18-24 分钟";
            case MIDDLE -> "中级：考察原理理解与常见场景设计；题目有区分度，整场约 30-40 分钟";
            case SENIOR -> "高级：考察架构设计、性能优化与疑难问题排查；题目深入，整场约 48-60 分钟";
        };

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("面试方向: ").append(direction).append("\n");
        userPrompt.append("难度要求：").append(difficultyText).append("\n");
        userPrompt.append("请生成 ").append(toGenerate).append(" 个主问题，作为候选人的技术基础题。\n");
        userPrompt.append("要求：\n");
        userPrompt.append("1. 从易到难排列，覆盖面试方向的核心知识\n");
        userPrompt.append("2. 结合真实面试场景，避免空泛提问\n");
        userPrompt.append("3. 每个问题只输出 question 字段，followups 为空数组\n");

        boolean hasResume = resumeText != null && !resumeText.isBlank();
        boolean hasPlan = planConcepts != null && !planConcepts.isEmpty();

        if (hasResume) {
            userPrompt.append("\n候选人简历内容");
            userPrompt.append(mixed ? "（出题权重 70%，应重点围绕简历中的项目与技术栈）" : "（出题依据）");
            userPrompt.append("：\n------------------------------------------\n");
            userPrompt.append(resumeText).append("\n");
            userPrompt.append("------------------------------------------\n");
        }
        if (hasPlan) {
            userPrompt.append("\n候选人学习方向知识点");
            userPrompt.append(mixed ? "（出题权重 30%，作为补充考察范围）" : "（出题依据）");
            userPrompt.append("：\n");
            for (String c : planConcepts) {
                userPrompt.append("- ").append(c).append("\n");
            }
        }

        InterviewQuestionResult result = invoker.invoke(
                SYSTEM_PROMPT,
                userPrompt.toString(),
                InterviewQuestionResult.class,
                provider
        );

        // 组装：第 1 题固定自我介绍 + LLM 生成的技术基础题（截断到目标数量）
        List<InterviewQuestionResult.InterviewQuestion> questions = new ArrayList<>();
        questions.add(new InterviewQuestionResult.InterviewQuestion(SELF_INTRO, List.of()));
        int idx = 0;
        for (InterviewQuestionResult.InterviewQuestion q : result.questions()) {
            if (questions.size() >= total) break;
            if (q.question() == null || q.question().isBlank()) continue;
            questions.add(new InterviewQuestionResult.InterviewQuestion(q.question(), List.of()));
            idx++;
        }
        // 兜底：LLM 给少了就补齐为通用兜底题
        while (questions.size() < total) {
            questions.add(new InterviewQuestionResult.InterviewQuestion(
                    "请谈谈你对" + direction + "中某一个你最熟悉的知识点的理解。", List.of()));
        }

        log.info("基础题生成完成: direction={}, difficulty={}, 题数={}", direction, difficulty, questions.size());
        return new InterviewQuestionResult(questions);
    }

}
