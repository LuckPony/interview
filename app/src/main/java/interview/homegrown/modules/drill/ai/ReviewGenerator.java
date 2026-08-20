package interview.homegrown.modules.drill.ai;

import interview.homegrown.common.ai.StructuredOutputInvoker;
import interview.homegrown.modules.drill.domain.DrillTurn;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 复盘报告生成器：基于题目、评分点、判分结果和整段对话，生成一道题的复盘——
 * 欠缺总结（用户哪里没打中）、解题思路、记忆口诀。
 *
 * <p>决策权在服务端：LLM 只负责把已有事实整理成可读的复盘，不新增评判。
 */
@Component
public class ReviewGenerator {

    private static final String SYSTEM = """
            你是一位资深技术老师。基于下面一道题：题目、评分点、判分结果（哪些点没打中）和整段对话，
            生成一份简洁的复盘报告：
            1. gapSummary：总结这次对话里学生欠缺在哪（依据判分未打中的点 + 学生实际回答），
               100 字内，直接说缺什么、漏了什么，不要绕；
            2. approach：这道题应该怎么想、怎么一步步作答（解题/答题思路），150 字内；
            3. mnemonic：一句朗朗上口、能帮学生记住核心要点的记忆口诀，30 字内。
            全部用中文。不要出现"你答得好不好"这类评判，只讲内容本身。严格遵循格式说明的 JSON。
            """;

    private final StructuredOutputInvoker invoker;

    public ReviewGenerator(StructuredOutputInvoker invoker) {
        this.invoker = invoker;
    }

    public ReviewOutput generate(String stem, String pointsJson, String byConceptJson,
                                 List<DrillTurn> turns, String context) {
        String history = turns.stream()
                .map(t -> {
                    StringBuilder s = new StringBuilder();
                    if (t.getRawAnswer() != null && !t.getRawAnswer().isBlank()) {
                        s.append("学生：").append(t.getRawAnswer()).append("\n");
                    }
                    if (t.getTutorText() != null && !t.getTutorText().isBlank()) {
                        s.append("老师讲解：").append(t.getTutorText()).append("\n");
                    }
                    return s.toString();
                })
                .collect(Collectors.joining("\n"));

        String user = String.format("""
                题目：
                %s

                评分点：
                %s

                判分结果：
                %s

                整段对话：
                %s
                %s
                """, nullTo(stem), nullTo(pointsJson), nullTo(byConceptJson),
                history.isBlank() ? "（无）" : history,
                (context == null || context.isBlank()) ? ""
                        : "\n\n学习上下文（学生进度 / 概念要点 / 用户资料 / 互联网补充，复盘可引用其真实内容）：\n" + context);

        return invoker.invoke(SYSTEM, user, ReviewOutput.class);
    }

    public record ReviewOutput(String gapSummary, String approach, String mnemonic) {
    }

    private static String nullTo(String s) {
        return s == null || s.isBlank() ? "（无）" : s;
    }
}
