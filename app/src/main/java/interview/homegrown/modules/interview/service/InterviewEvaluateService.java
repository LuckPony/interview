package interview.homegrown.modules.interview.service;

import interview.homegrown.common.ai.StructuredOutputInvoker;
import interview.homegrown.modules.interview.model.InterviewEvaluationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 面试评估服务
 * 对面试中所有的问答进行统一评估：
 * 总分 + 逐题评分 + 优势 + 改进建议
 */

@Service
public class InterviewEvaluateService {

    private static final Logger log = LoggerFactory.getLogger(InterviewEvaluateService.class);

    private final StructuredOutputInvoker invoker;

    public InterviewEvaluateService(StructuredOutputInvoker invoker) {
        this.invoker = invoker;
    }

    private static final String SYSTEM_PROMPT = """
            你是一位严格、专业的面试评估专家。
            请根据面试中候选人的回答，评估其整体表现，以 JSON 格式输出。
            """;

    /**
     * 评估面试表现
     * @param sessionId  会话 id
     * @param questions  面试题目（顺序）
     * @param answers    候选人答案（与题目一一对应，可能为空）
     * @param skillName  面试方向名称
     * @param provider   LLM Provider
     */
    public InterviewEvaluationResult evaluate(String sessionId,
                                              List<String> questions,
                                              List<String> answers,
                                              String skillName,
                                              String provider){

        //只评估已经作答的题目
        StringBuilder qaText = new StringBuilder();
        for(int i = 0; i < questions.size(); i++ ){

            qaText.append("第").append(i+1).append("题: ").append(questions.get(i)).append("\n");
            String answer = (i<answers.size() && answers.get(i) != null) ? answers.get(i) : null;
            qaText.append("候选人回答：").append(answer).append("\n\n");
        }

        String userPrompt = """
                面试方向：__SKILL__
                
                以下是面试问答记录：

                __QA__
                评估要求：
                1. totalScore 为 0-100 的整数，代表整体面试表现
                2. questionEvaluations 与题目一一对应，每个包含 score(0-100) 和 feedback
                3. strengths 给出 2-3 条候选人的优势
                4. improvements 给出 2-3 条具体的改进建议
                """
                .replace("__SKILL__", skillName == null ? "" : skillName)
                .replace("__QA__", qaText.toString());

        InterviewEvaluationResult result = invoker.invoke(
                SYSTEM_PROMPT,
                userPrompt,
                InterviewEvaluationResult.class,
                provider
        );

        log.info("面试评估完成: sessionId={}, 总分={}", sessionId, result.totalScore());
        return result;
    }

}
