package interview.homegrown.modules.knowledge.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeControllerPromptTest {

    @Test
    @DisplayName("追问请求会携带最近的用户与 AI 对话并把当前问题放在末尾")
    void shouldBuildPromptWithConversationHistory() {
        KnowledgeController.AskRequest request = new KnowledgeController.AskRequest(
                "它在高并发下有什么问题？",
                null,
                List.of(
                        new KnowledgeController.Msg("user", "什么是单例模式？"),
                        new KnowledgeController.Msg("ai", "单例模式保证一个类只有一个实例。")
                )
        );

        String prompt = KnowledgeController.buildUserPrompt(request);

        assertThat(prompt)
                .contains("用户：什么是单例模式？")
                .contains("AI：单例模式保证一个类只有一个实例。")
                .endsWith("当前问题：它在高并发下有什么问题？");
    }

    @Test
    @DisplayName("历史过长时只保留最近十二条消息")
    void shouldKeepOnlyRecentMessages() {
        List<KnowledgeController.Msg> history = java.util.stream.IntStream.rangeClosed(1, 14)
                .mapToObj(index -> new KnowledgeController.Msg(
                        index % 2 == 0 ? "ai" : "user",
                        "消息" + index
                ))
                .toList();

        String prompt = KnowledgeController.buildUserPrompt(
                new KnowledgeController.AskRequest("继续", null, history)
        );

        assertThat(prompt)
                .doesNotContain("消息1\n", "消息2\n")
                .contains("消息3", "消息14")
                .endsWith("当前问题：继续");
    }
}
