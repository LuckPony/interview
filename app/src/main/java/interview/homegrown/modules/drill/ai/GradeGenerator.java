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
        return grade(stem, rawAnswer, groups, context, null, List.of());
    }

    /**
     * @param conversation 对话实录（老师实际问过的问题 + 学生回答），可为 null。
     * @param followups    出题时预生成的追问小问清单（用于判断评分点是否对应未问到的追问），可为空。
     *                     提供对话实录后，未被问过的问题对应的评分点应判 NA（不计分），
     *                     避免把「根本没考到的点」误判为 MISS 拉低分数。
     */
    public GradeOutput grade(String stem, String rawAnswer, List<ConceptPointGroup> groups,
                             String context, String conversation, List<String> followups) {
        String system = """
                你是评分器。判分分两步：
                第一步，通读「对话实录」，确定老师实际问过哪些问题：主问(stem)一定被问过；
                追问小问对照「追问小问清单」与实录，只把老师消息里确实出现过的追问算作被问到。
                第二步，只针对「被问过的问题」判分，按 conceptIndex 分组输出 pointResults。

                判分基准（只评被问到的内容）：
                - 主问(stem)一定被问过：与主问直接相关的评分点必须全部列出并判 HIT/PARTIAL/MISS，严禁判 NA。
                - 只属于「没被问到的追问小问」的评分点 → 判 NA（未考察，不计分）。
                - 严禁把「没被问到」的内容当作 MISS 扣分；也严禁把「被问到但答错/没答」的内容判 NA。

                理解型判分（看意思，不看措辞）：
                - HIT：用户回答的意思与评分点一致且正确。允许换说法、用自己的例子、改变表达结构；
                  只要核心意思表达到了，即使没用评分点的原词、特定术语或句式，也必须判 HIT。
                - PARTIAL：意思基本正确，但有明显遗漏或部分不准确。
                - MISS：完全没答到，或核心意思答错。
                - 严禁因为措辞不同、没用到特定关键词、回答更简练或口语化，就把意思一致的答案判成 MISS/PARTIAL。

                evidence 必须是用户答案中的原话片段（逐字复制），不得改写或编造；
                若判 MISS 或 NA 则 evidence 留空字符串。
                必须按 conceptIndex 分组输出 byConcept，且覆盖下面列出的全部分组。
                extraCorrect 记录用户答对但不在评分点里的内容，factualErrors 记录事实性错误。
                判分依据仅限：题干、评分点、用户答案、对话实录（如有）、以及给定学习上下文；
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

        String conversationBlock;
        if (conversation == null || conversation.isBlank()) {
            conversationBlock = "\n\n说明：本题无对话实录，所有评分点均视为已被考到，只判 HIT/PARTIAL/MISS，不得判 NA。";
        } else {
            conversationBlock = "\n\n对话实录（老师实际问过的问题 + 学生回答）。"
                    + "判分前必须先据此确定被问过的问题：主问恒在；追问以实录中老师消息为准；"
                    + "没被问到的追问对应的评分点判 NA：\n" + conversation;
        }

        String followupBlock;
        if (followups == null || followups.isEmpty()) {
            followupBlock = "";
        } else {
            followupBlock = "\n\n出题时预生成的追问小问清单（判分时对照对话实录判断哪几条被实际问到；"
                    + "没被问到的追问对应的评分点判 NA，不判 MISS）：\n"
                    + IntStream.range(0, followups.size())
                    .mapToObj(i -> (i + 1) + ". " + followups.get(i))
                    .reduce((a, b) -> a + "\n" + b)
                    .orElse("");
        }

        String user = String.format("""
                题干：%s

                待判分组：
                %s
                %s

                用户答案：
                %s
                %s
                %s
                """, stem, groupBlock, followupBlock, rawAnswer, conversationBlock,
                (context == null || context.isBlank()) ? "" : "\n\n学习上下文（判分时可对照，但只依据其中真实内容）：\n" + context);

        return invoker.invoke(system, user, GradeOutput.class);
    }
}
