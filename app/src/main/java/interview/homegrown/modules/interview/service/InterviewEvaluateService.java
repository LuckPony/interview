package interview.homegrown.modules.interview.service;

import interview.homegrown.common.ai.StructuredOutputInvoker;
import interview.homegrown.common.exception.BusinessException;
import interview.homegrown.common.exception.ErrorCode;
import interview.homegrown.modules.interview.model.InterviewEvaluationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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
            你必须只根据候选人的实际回答评分，不得把问题中已有的信息当作候选人已回答的内容，
            也不得臆造候选人没有表达过的证据。请以 JSON 格式输出可复核的评估结果。
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
                1. questionEvaluations 必须与上面的问答严格一一对应，顺序和数量都不能改变；主问题和追问都要单独评估。
                2. 每题根据题目考查目标动态拆分 3-5 个 scoringPoints，不要机械套用同一套话术。可以从结论正确性、
                   技术原理、实现细节、方案权衡、边界与异常等维度中选择真正适合该题的评分点。
                3. 每个 scoringPoint 必须包含：
                   - criterion：具体评分标准；
                   - maxScore：该项满分，且同一题所有 maxScore 之和必须为 100；
                   - awardedScore：该项实得分，只能在 0 到 maxScore 之间；
                   - status：只能是 ACHIEVED、PARTIAL、MISSED；
                   - evidence：候选人回答中支持该判断的原意概括；没有提到时必须写“回答中未提供”；
                   - reason：为什么得到或失去这部分分数，指出缺失内容或错误点。
                4. 每题 score 必须等于该题所有 scoringPoints 的 awardedScore 之和，feedback 给出该题的综合反馈。
                5. totalScore 为全部题目 score 的算术平均值（四舍五入到整数），范围 0-100。
                6. strength 给出 2-3 条候选人的优势，improvements 给出 2-3 条具体改进建议。
                """
                .replace("__SKILL__", skillName == null ? "" : skillName)
                .replace("__QA__", qaText.toString());

        InterviewEvaluationResult rawResult = invoker.invoke(
                SYSTEM_PROMPT,
                userPrompt,
                InterviewEvaluationResult.class,
                provider
        );
        InterviewEvaluationResult result = normalize(rawResult, questions.size());

        log.info("面试评估完成: sessionId={}, 总分={}", sessionId, result.totalScore());
        return result;
    }

    /**
     * LLM 偶尔会发生算术误差。最终得分以可见评分点为唯一依据，并在服务端统一权重、状态和总分，
     * 保证页面展示的明细始终能准确加总到题目分与总分。
     */
    private InterviewEvaluationResult normalize(InterviewEvaluationResult result, int questionCount) {
        if (result == null || result.questionEvaluations() == null
                || result.questionEvaluations().size() != questionCount) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "AI 评估结果与问答数量不一致，请重新评估");
        }

        List<InterviewEvaluationResult.QuestionEvaluation> evaluations = new ArrayList<>();
        for (int i = 0; i < result.questionEvaluations().size(); i++) {
            InterviewEvaluationResult.QuestionEvaluation evaluation = result.questionEvaluations().get(i);
            if (evaluation == null || evaluation.scoringPoints() == null
                    || evaluation.scoringPoints().isEmpty()) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                        "AI 评估结果缺少第 " + (i + 1) + " 题的评分明细，请重新评估");
            }
            List<InterviewEvaluationResult.ScoringPoint> points = normalizePoints(evaluation.scoringPoints());
            int score = points.stream().mapToInt(InterviewEvaluationResult.ScoringPoint::awardedScore).sum();
            evaluations.add(new InterviewEvaluationResult.QuestionEvaluation(
                    score,
                    textOrDefault(evaluation.feedback(), "请结合下方评分点查看本题表现。"),
                    points));
        }

        int totalScore = (int) Math.round(evaluations.stream()
                .mapToInt(InterviewEvaluationResult.QuestionEvaluation::score)
                .average()
                .orElse(0));
        return new InterviewEvaluationResult(
                totalScore,
                List.copyOf(evaluations),
                result.strength() == null ? List.of() : result.strength(),
                result.improvements() == null ? List.of() : result.improvements());
    }

    private List<InterviewEvaluationResult.ScoringPoint> normalizePoints(
            List<InterviewEvaluationResult.ScoringPoint> rawPoints) {
        List<InterviewEvaluationResult.ScoringPoint> usable = rawPoints.stream()
                .filter(point -> point != null && point.maxScore() > 0)
                .toList();
        if (usable.isEmpty()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "AI 评估返回了无效评分权重，请重新评估");
        }

        int originalRemaining = usable.stream()
                .mapToInt(InterviewEvaluationResult.ScoringPoint::maxScore)
                .sum();
        int normalizedRemaining = 100;
        List<InterviewEvaluationResult.ScoringPoint> normalized = new ArrayList<>();
        for (int i = 0; i < usable.size(); i++) {
            InterviewEvaluationResult.ScoringPoint point = usable.get(i);
            int itemsLeft = usable.size() - i;
            int maxScore;
            if (itemsLeft == 1) {
                maxScore = normalizedRemaining;
            } else {
                maxScore = (int) Math.round((double) point.maxScore() / originalRemaining * normalizedRemaining);
                maxScore = Math.max(1, Math.min(maxScore, normalizedRemaining - itemsLeft + 1));
            }
            double achievementRatio = (double) Math.max(0, Math.min(point.awardedScore(), point.maxScore()))
                    / point.maxScore();
            int awardedScore = Math.max(0, Math.min(maxScore, (int) Math.round(achievementRatio * maxScore)));
            String status = awardedScore == 0 ? "MISSED" : awardedScore == maxScore ? "ACHIEVED" : "PARTIAL";
            normalized.add(new InterviewEvaluationResult.ScoringPoint(
                    textOrDefault(point.criterion(), "评分点 " + (i + 1)),
                    maxScore,
                    awardedScore,
                    status,
                    textOrDefault(point.evidence(), "回答中未提供"),
                    textOrDefault(point.reason(), "该评分点未提供独立说明")));
            originalRemaining -= point.maxScore();
            normalizedRemaining -= maxScore;
        }
        return List.copyOf(normalized);
    }

    private String textOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

}
