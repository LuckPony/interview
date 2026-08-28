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
        return grade(stem, rawAnswer, groups, context, null);
    }

    /**
     * @param conversation 对话实录（老师实际问过的问题 + 学生回答），可为 null。
     */
    public GradeOutput grade(String stem, String rawAnswer, List<ConceptPointGroup> groups,
                             String context, String conversation) {
        String system = """
                你是评分器。先确定合法评分范围，再逐点判定：
                1. 合法评分范围只来自主问(stem)明确要求学生回答的内容。判断标准是：学生只看 stem，
                   是否能合理知道自己需要回答该评分点。
                2. 对话中的追问是教学过程，不得扩大本题量化评分范围。
                   评分点若 stem 从未要求该方法/API/边界/场景，必须判 NA，不计分，
                   即使老师后来追问过也一样。
                3. 对合法范围内的评分点，再结合用户答案与对话实录判 HIT/PARTIAL/MISS。

                判分基准：
                - 与主问直接相关且由 stem 明确要求的评分点，必须判 HIT/PARTIAL/MISS。
                - stem 没有明确要求的评分点，包括尚未提出或后来提出的追问扩展内容，一律判 NA。
                - 严禁把“同一知识点通常还应该会什么”作为扣分依据，严禁用完整知识清单代替题面要求。
                - 严禁把未要求内容当作 MISS 扣分，也严禁把已明确要求但答错或没答的内容判 NA。
                - 评分只依据学生在「获得完整答案之前」独立给出的回答：若对话实录显示老师在某条消息中
                  已经直接给出完整答案、完整代码或把关键解题思路讲到位（含点击「看答案」揭示答案的情况），
                  该消息之后出现的『学生：』回答一律不作为得分依据——即使内容正确也不得判 HIT/PARTIAL，
                  判 NA（那是复述老师给的答案，不是学生独立作答）。对话实录中没有老师泄底时，正常判分。

                理解型判分（看意思，不看措辞，务必宽松）：
                - 判断唯一标准是"学生是否答到了评分点要考察的那个核心意思（knowledge point）"，
                  而不是"是否和评分点的标准表述一致"。
                - HIT：学生回答的核心意思与评分点一致且正确。允许换说法、用自己的例子、口语化、更简练、
                  调整表达结构、不使用评分点的原词/术语/句式。只要核心意思表达到了，即使漏掉了非核心的
                  修饰语、例子、次要分支，也必须判 HIT。
                - PARTIAL：核心意思基本正确，但有"明显且实质"的遗漏或不准确（例如漏掉了一个关键前提、
                  把核心概念说错了一半）。仅因表达不够完整、不够精炼、举例不同，不判 PARTIAL。
                - MISS：学生根本没提到这个核心意思，或核心意思答错（与评分点相反/无关）。
                - 严禁因为措辞不同、没用特定关键词、回答更简练、更口语化、或用了另一种等价说法，
                  就把意思对的答案判成 MISS 或 PARTIAL。
                - 严禁用"完美标准答案"倒推：学生答对核心即可给 HIT，不要因为他没有把所有细节、
                  所有边界、所有前提都列全就扣分。
                - 一句话原则：只要学生「用对的方式答到了对的点」，就是 HIT；尤其不要因为表达风格
                  或详略差异而压分。

                代码/实现型题判分（题干要求"写出代码/SQL/命令/配置"时适用）：
                - 直接阅读学生提交的代码/命令/配置，判断它是否正确实现了题干要求的行为；
                  学生不需要用自然语言复述原理，代码本身就是作答，不得因"没有解释"而判 MISS/PARTIAL。
                - HIT：代码能正确实现该评分点对应的行为，且关键边界处理正确；允许不同写法、
                  不同命名、不同语言风格，只要语义等价即可。
                - PARTIAL：思路对、主体结构对，但有实质缺陷（漏了关键边界、逻辑有 bug、复杂度明显不达标且题干要求了）。
                - MISS：代码没有实现该行为，或实现方向错误。
                - evidence 可摘录代码里的关键片段或关键符号（逐字复制），不得改写或编造。
                评分范围仍只限题干明确要求的点：题干没要求复杂度就不考复杂度，题干没要求异常处理就不考异常处理。

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
            conversationBlock = "\n\n对话实录（用于核验学生对主问的回答，可帮助理解上下文）。"
                    + "评分只依据标注为「首次独立作答」的学生消息；标注为「追问后的修正/复述，不计分」的后续消息"
                    + "只能用于判断老师是否已经泄底（若老师已给出答案，其后的回答判 NA），"
                    + "绝不得当作独立作答来给 HIT。任何无法从 stem 直接推出的评分点仍必须判 NA：\n"
                    + conversation;
        }

        String user = String.format("""
                题干：%s

                待判分组：
                %s

                用户答案：
                %s
                %s
                %s
                """, stem, groupBlock, rawAnswer, conversationBlock,
                (context == null || context.isBlank()) ? "" : "\n\n学习上下文（判分时可对照，但只依据其中真实内容）：\n" + context);

        return invoker.invoke(system, user, GradeOutput.class);
    }
}
