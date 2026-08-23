package interview.homegrown.modules.interview.service;

import interview.homegrown.common.ai.StructuredOutputInvoker;
import interview.homegrown.modules.interview.model.InterviewDifficulty;
import interview.homegrown.modules.interview.model.InterviewQuestionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 面试出题服务
 * 依据 = 简历（70% 权重）+ 学习方向知识点（30% 权重），可只选其一（此时按单一来源出题）。
 */
@Service
public class InterviewQuestionService {

    private static final Logger log = LoggerFactory.getLogger(InterviewQuestionService.class);

    private final StructuredOutputInvoker invoker;
    private final InterviewSkillService skillService;

    public InterviewQuestionService(StructuredOutputInvoker invoker, InterviewSkillService skillService) {
        this.invoker = invoker;
        this.skillService = skillService;
    }

    private static final String SYSTEM_PROMPT = """
            你是一位资深的技术面试官，擅长针对不同技术方向设计高质量的面试题。
            请输出 JSON 格式的题目列表，每个题目包含主问题和若干追问。
            """;

    /**
     * 生成面试题目
     *
     * @param skillName      面试方向名称（可空：方向由简历/学习方向决定）
     * @param difficulty     难度
     * @param questionCount  主问题数量
     * @param resumeText     简历文本（可空；非空时权重 70%）
     * @param planConcepts   学习方向知识点（可空；非空时权重 30%）
     * @param mixed          简历与学习方向是否都提供（都提供时提示 LLM 按 70/30 占比）
     * @param provider       LLM Provider
     */
    public InterviewQuestionResult generateQuestions(String skillName,
                                                     InterviewDifficulty difficulty,
                                                     int questionCount,
                                                     String resumeText,
                                                     List<String> planConcepts,
                                                     boolean mixed,
                                                     String provider){

        String direction = (skillName != null && !skillName.isBlank())
                ? skillName
                : (planConcepts != null && !planConcepts.isEmpty() ? "学习方向知识点" : "候选人简历相关技术栈");
        int followUpCount = skillService.getFollowUpCount();

        String difficultyText = switch (difficulty){
            case JUNIOR -> "初级：考察基础概念掌握与简单应用";
            case MIDDLE -> "中级：考察原理理解与常见场景设计";
            case SENIOR -> "高级：考察架构设计、性能优化与疑难问题排查";
        };

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("面试方向: ").append(direction).append("\n");
        userPrompt.append("难度要求：").append(difficultyText).append("\n");
        userPrompt.append("题目数量：").append(questionCount).append(" 个主问题\n");
        userPrompt.append("每个主问题附带 ").append(followUpCount).append(" 个追问，追问用于深挖候选人的理解深度。\n");

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

        userPrompt.append("\n要求：\n");
        userPrompt.append("1. 题目要有区分度，从易到难排列\n");
        userPrompt.append("2. 结合真实面试场景，避免空泛提问\n");
        userPrompt.append("3. 追问要能引导候选人深入思考\n");
        userPrompt.append("4. 预计整场面试耗时控制在 30-40 分钟，题目量与此匹配\n");

        InterviewQuestionResult result = invoker.invoke(
                SYSTEM_PROMPT,
                userPrompt.toString(),
                InterviewQuestionResult.class,
                provider
        );

        log.info("出题完成: direction={}, difficulty={}, 题目数={}, resume={}, plans={}",
                direction, difficulty, result.questions().size(), hasResume, hasPlan);
        return result;
    }

}
