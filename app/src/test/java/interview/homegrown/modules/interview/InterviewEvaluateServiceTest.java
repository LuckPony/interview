package interview.homegrown.modules.interview;

import interview.homegrown.common.ai.StructuredOutputInvoker;
import interview.homegrown.common.exception.BusinessException;
import interview.homegrown.modules.interview.model.InterviewEvaluationResult;
import interview.homegrown.modules.interview.service.InterviewEvaluateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InterviewEvaluateServiceTest {

    private final StructuredOutputInvoker invoker = mock(StructuredOutputInvoker.class);
    private final InterviewEvaluateService service = new InterviewEvaluateService(invoker);

    @Test
    @DisplayName("逐题得分和总分以详细评分点为准")
    void shouldCalculateScoresFromScoringPoints() {
        InterviewEvaluationResult raw = new InterviewEvaluationResult(
                99,
                List.of(
                        evaluation(88, List.of(
                                point("说明核心原理", 60, 60),
                                point("覆盖异常场景", 40, 0))),
                        evaluation(10, List.of(
                                point("给出实现步骤", 40, 20),
                                point("说明方案权衡", 60, 60)))),
                List.of("表达清晰"),
                List.of("补充边界条件"));
        when(invoker.invoke(anyString(), anyString(), eq(InterviewEvaluationResult.class), any()))
                .thenReturn(raw);

        InterviewEvaluationResult result = service.evaluate(
                "session-1", List.of("问题一", "追问一"), List.of("回答一", "回答二"), "Java", null);

        assertThat(result.questionEvaluations()).extracting(InterviewEvaluationResult.QuestionEvaluation::score)
                .containsExactly(60, 80);
        assertThat(result.totalScore()).isEqualTo(70);
        assertThat(result.questionEvaluations().get(0).scoringPoints())
                .extracting(InterviewEvaluationResult.ScoringPoint::status)
                .containsExactly("ACHIEVED", "MISSED");
    }

    @Test
    @DisplayName("评估条数与问答不一致时拒绝保存错误评分")
    void shouldRejectMismatchedEvaluationCount() {
        InterviewEvaluationResult raw = new InterviewEvaluationResult(
                60,
                List.of(evaluation(60, List.of(point("说明核心原理", 100, 60)))),
                List.of(),
                List.of());
        when(invoker.invoke(anyString(), anyString(), eq(InterviewEvaluationResult.class), any()))
                .thenReturn(raw);

        assertThatThrownBy(() -> service.evaluate(
                "session-2", List.of("问题一", "问题二"), List.of("回答一", "回答二"), "Java", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("问答数量不一致");
    }

    private InterviewEvaluationResult.QuestionEvaluation evaluation(
            int score, List<InterviewEvaluationResult.ScoringPoint> points) {
        return new InterviewEvaluationResult.QuestionEvaluation(score, "综合反馈", points);
    }

    private InterviewEvaluationResult.ScoringPoint point(String criterion, int maxScore, int awardedScore) {
        return new InterviewEvaluationResult.ScoringPoint(
                criterion, maxScore, awardedScore, "PARTIAL", "回答依据", "评分说明");
    }
}
