package interview.homegrown.modules.drill.ai;

import interview.homegrown.common.ai.StructuredOutputInvoker;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.IntStream;

/**
 * 判分生成器：复用 StructuredOutputInvoker。只做"逐点分类"（HIT/PARTIAL/MISS），不做打分。
 *
 * <p>两条命门约束：
 * <ul>
 *   <li>evidence 必须是用户原话片段（逐字），不得改写或编造 —— 画像诚实性的根。</li>
 *   <li>输出 schema 里没有 score 字段，模型物理上写不出分数 —— 分由服务端算。</li>
 * </ul>
 */
@Component
public class GradeGenerator {

    private final StructuredOutputInvoker invoker;

    public GradeGenerator(StructuredOutputInvoker invoker) {
        this.invoker = invoker;
    }

    /** 每个概念一组待判评分点，index 与出题时一致 */
    public record ConceptPointGroup(int conceptIndex, String conceptName, List<String> points) {
    }

    public GradeOutput grade(String stem, String rawAnswer, List<ConceptPointGroup> groups,
                             String context) {
        String system = """
                你是严格的评分器。对每个评分点(point)，判断用户答案的达成度：
                - HIT：明确覆盖且正确
                - PARTIAL：部分覆盖或有小遗漏
                - MISS：未覆盖或错误
                evidence 必须是用户答案中的原话片段（逐字复制），不得改写或编造；
                若判 MISS 则 evidence 留空字符串。
                必须按 conceptIndex 分组输出 byConcept，且覆盖下面列出的全部分组。
                extraCorrect 记录用户答对但不在评分点里的内容，factualErrors 记录事实性错误。
                判分依据仅限：题干、评分点、用户答案、以及给定学习上下文；
                不得自行补充外部知识、不得联网搜索、不得凭印象脑补材料之外的内容。
                不要给出分数，不要给出标准答案，不要写总结。
                不要使用中文破折号，输出严格遵循格式说明的 JSON。""";

        String groupBlock = IntStream.range(0, groups.size())
                .mapToObj(i -> {
                    ConceptPointGroup g = groups.get(i);
                    return String.format("[%d] 概念：%s\n    评分点：%s",
                            g.conceptIndex(), g.conceptName(), String.join("；", g.points()));
                })
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");

        String user = String.format("""
                题干：%s

                待判分组：
                %s

                用户答案：
                %s
                %s
                """, stem, groupBlock, rawAnswer,
                (context == null || context.isBlank()) ? "" : "\n\n学习上下文（判分时可对照，但只依据其中真实内容）：\n" + context);

        return invoker.invoke(system, user, GradeOutput.class);
    }
}
