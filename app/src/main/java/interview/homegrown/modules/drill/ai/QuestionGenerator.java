package interview.homegrown.modules.drill.ai;

import interview.homegrown.common.ai.StructuredOutputInvoker;
import interview.homegrown.modules.drill.domain.ConceptRef;
import interview.homegrown.modules.drill.domain.ConceptRole;
import interview.homegrown.modules.drill.domain.ProbeType;
import interview.homegrown.modules.drill.domain.ResponseFormat;
import interview.homegrown.modules.drill.domain.SelectedTask;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.IntStream;

/**
 * 出题生成器：复用项目已有的 StructuredOutputInvoker（Spring AI ChatClient + 重试 + 结构化输出）。
 *
 * <p>服务端已经把四维签名（concept_ids / probe_type / answer_mode / response_format）和 arity 都定死了，
 * LLM 只负责把签名"填成一道人话题目"。它无权决定考哪个概念、几个概念、用什么认知动作。
 *
 * <p>历史题干以"避免雷同"的形式注入，这是去重三闸里的<b>软闸</b>；硬闸在 QuestionService。
 */
@Component
public class QuestionGenerator {

    private final StructuredOutputInvoker invoker;

    public QuestionGenerator(StructuredOutputInvoker invoker) {
        this.invoker = invoker;
    }

    public record Prompt(String system, String user) {
    }

    /** 组装出题 prompt（供流式出题复用，与同步 generate 完全一致）。 */
    public Prompt prompt(SelectedTask task, ProbeType probeType, ResponseFormat format,
                         List<String> avoidStems, String referenceText) {
        String system = """
                你是一位经验丰富的技术导师兼面试官，现在正亲自向一位学习者提问。
                你出题时要用第一人称、直接面向学习者：
                - 可以说"你怎么看""你觉得这里会出什么问题""如果让你来设计""请解释给我听"。
                - 严禁使用"你在跟候选人讨论""他抛出一个观点""有人提出""他们讨论"等第三人称旁观者叙事。
                - 严禁让学习者去评判/反驳一个虚构第三方的观点，把问题直接抛给学习者本人。
                题目要像真实面试或课堂追问那样自然：给出一个具体场景或一个待解决的设计问题，
                让学习者在回答中暴露出对概念边界的理解深度。
                题干要让学习者能读明白：必要时用 1-2 句话交代背景，再抛出核心问题；
                如果概念较抽象，可先给出一个贴近工程的例子，再引导学习者分析其原理或边界。
                题干用 Markdown 排版：涉及代码时用 ``` 代码块包裹并注明语言，关键术语用 **加粗**，
                必要时可用列表或 ### 小标题，让学习者阅读更清楚。
                题干必须聚焦一个核心问题，一次最多 2-3 个递进的小问，不要堆砌多个无关小问题，
                不要一段话里塞四五个问句。
                如果概念是算法 / 数据结构 / 代码实现类：请在题干里指定一道具体的 LeetCode 题目
                （题号 + 题名），并明确提示"先到 LeetCode 完成这道题，然后把通过全部用例的代码
                粘贴到输入框"；代码题可以多问几轮（复杂度、边界、优化、扩展），不受 3 问限制。
                只产出题目与评分点，严禁给出答案、解析或提示。
                评分点(points)必须是可客观核验的知识点，每条带权重 weight(1-3，越核心越大)。
                必须按概念分组输出 byConcept，conceptIndex 使用下面清单里给出的序号。
                不要使用中文破折号，输出严格遵循格式说明的 JSON。""";

        String conceptList = renderConcepts(task.concepts());
        String avoidBlock = avoidStems.isEmpty() ? "（暂无历史题目）"
                : String.join("\n", avoidStems.stream().map(s -> "- " + s).toList());

        String user = String.format("""
                本题涉及的概念清单（序号即 conceptIndex）：
                %s

                出题要求：
                - 认知动作类型：%s
                - 作答形态：%s
                - 概念数 arity：%d（必须恰好覆盖上面全部概念，不得增删）
                - 题干(stem)必须是面试官/老师直接向学习者本人提问：
                  构造场景、抛出设计问题、追问边界情况、或让学习者解释一个真实会遇到的难题。
                  禁止"你跟候选人讨论时，他提出..."这种让学习者评判第三方的写法。
                - 题干用 Markdown 排版：涉及代码时用 ``` 代码块包裹（注明语言），关键术语加粗，
                  可适当用列表或小标题，方便学习者阅读。
                - 如果题目涉及依赖分析、框架机制或分层设计，请直接给出具体代码/配置/链路背景，
                  然后问"你怎么看""这里有什么问题""为什么""会验证什么、不会验证什么"。
                - PRIMARY 概念是本题真正要推进的目标，评分点应覆盖其核心，3-5 条
                - ANCHOR 概念是用户已掌握的挂靠点，评分点只考它与 PRIMARY 的关系或边界，1-3 条，
                  不要再考它的基础定义
                - byConcept 必须为每个 conceptIndex 各出一组评分点

                以下是该知识点已出过的题干，新题必须在提问角度上明显不同，且不能再用"候选人/他/他们"叙事：
                %s
                """, conceptList, probeType, format, task.arity(), avoidBlock);

        // 资料注入：用户基于某本书/项目资料学习时，评分点须以资料为准，不得杜撰。
        if (referenceText != null && !referenceText.isBlank()) {
            user += "\n\n以下是本题概念对应的权威资料（来自用户上传的学习资料），评分点须以此为准，"
                    + "不得杜撰或超出资料范围：\n" + referenceText;
        }

        return new Prompt(system, user);
    }

    public GeneratedQuestion generate(SelectedTask task, ProbeType probeType,
                                      ResponseFormat format, List<String> avoidStems,
                                      String referenceText) {
        Prompt p = prompt(task, probeType, format, avoidStems, referenceText);
        return invoker.invoke(p.system(), p.user(), GeneratedQuestion.class);
    }

    private String renderConcepts(List<ConceptRef> concepts) {
        return IntStream.range(0, concepts.size())
                .mapToObj(i -> {
                    ConceptRef c = concepts.get(i);
                    String roleHint = c.role() == ConceptRole.PRIMARY
                            ? "PRIMARY 目标概念" : "ANCHOR 已掌握锚点";
                    return String.format("[%d] %s（主题：%s，认知层 L%d，角色：%s）说明：%s",
                            i, c.name(), c.topic(), c.layer(), roleHint, c.description());
                })
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }
}
