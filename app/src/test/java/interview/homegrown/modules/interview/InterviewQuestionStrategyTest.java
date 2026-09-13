package interview.homegrown.modules.interview;

import interview.homegrown.common.ai.StructuredOutputInvoker;
import interview.homegrown.modules.interview.model.InterviewDifficulty;
import interview.homegrown.modules.interview.model.InterviewQuestionResult;
import interview.homegrown.modules.interview.service.FollowupGeneratorService;
import interview.homegrown.modules.interview.service.InterviewQuestionService;
import interview.homegrown.modules.interview.service.InterviewSkillService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InterviewQuestionStrategyTest {

    private final StructuredOutputInvoker invoker = mock(StructuredOutputInvoker.class);

    @Test
    @DisplayName("一面按七个槽位规划并始终补齐为八道不重复主问题")
    void shouldBuildEightDiverseFirstRoundQuestions() {
        InterviewQuestionResult generated = new InterviewQuestionResult(List.of(
                new InterviewQuestionResult.InterviewQuestion("请说明项目中的登录流程。", List.of()),
                new InterviewQuestionResult.InterviewQuestion("请说明项目中的登录流程。", List.of())));
        when(invoker.invoke(anyString(), anyString(), eq(InterviewQuestionResult.class), eq("deepseek")))
                .thenReturn(generated);
        InterviewQuestionService service = new InterviewQuestionService(
                invoker, mock(InterviewSkillService.class));

        InterviewQuestionResult result = service.generateBaseQuestions(
                "Java 后端",
                InterviewDifficulty.JUNIOR,
                "项目 A：使用 Spring Boot、PostgreSQL 和 Redis，实现登录与异步任务。",
                List.of(),
                false,
                "deepseek",
                null);

        assertThat(result.questions()).hasSize(8);
        assertThat(result.questions().stream().map(InterviewQuestionResult.InterviewQuestion::question))
                .doesNotHaveDuplicates();

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(invoker).invoke(anyString(), prompt.capture(), eq(InterviewQuestionResult.class), eq("deepseek"));
        assertThat(prompt.getValue())
                .contains("面试轮次：一面")
                .contains("严格生成剩余 7 道")
                .contains("一面七个覆盖槽位")
                .contains("同一项目/章节最多 2 道");
    }

    @Test
    @DisplayName("达到两次追问上限后直接进入下一主问题且不再调用模型")
    void shouldStopAtMaximumFollowUps() {
        FollowupGeneratorService service = new FollowupGeneratorService(invoker);

        var decision = service.decideFollowUp(
                InterviewDifficulty.MIDDLE,
                "Java 后端",
                "Redis Stream 如何保证任务可靠性？",
                "Pending 消息如何回收？",
                "通过定时扫描并重新认领。",
                2,
                2,
                "deepseek");

        assertThat(decision.shouldFollowUp()).isFalse();
        verify(invoker, never()).invoke(
                anyString(), anyString(), eq(FollowupGeneratorService.FollowUpDecision.class), anyString());
    }
}
