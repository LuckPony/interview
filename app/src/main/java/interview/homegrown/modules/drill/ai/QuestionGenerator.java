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

    /** 组装出题 prompt（供流式出题复用，与同步 generate 完全一致）。
     *
     * @param contextText 学习上下文（学生进度 + 概念要点 + 用户资料块 + 互联网补充），可为 null
     */
    public Prompt prompt(SelectedTask task, ProbeType probeType, ResponseFormat format,
                         List<String> avoidStems, String contextText) {
        String system = """
                你是一位经验丰富的技术导师兼面试官，现在正亲自向一位学习者提问。
                你出题时要用第一人称、直接面向学习者：
                - 可以说"你怎么看""你觉得这里会出什么问题""如果让你来设计""请解释给我听"。
                - 严禁使用"你在跟候选人讨论""他抛出一个观点""有人提出""他们讨论"等第三人称旁观者叙事。
                - 严禁让学习者去评判/反驳一个虚构第三方的观点，把问题直接抛给学习者本人。
                题目要像耐心老师的课堂练习，而不是压力面试：给出一个具体场景或一个待解决的问题，
                让学习者展示当前层级应掌握的核心理解。默认从基础、常见、可回答的问题开始，
                不使用偏门术语、脑筋急转弯、罕见边界或超出当前认知层的系统设计；必须使用专业词时，
                先用一句白话解释它。学习上下文显示掌握度低时，只问定义、最小示例或最常见用法；
                掌握度提高后才逐步加入原因、边界和权衡。
                题干要让学习者能读明白：必要时用 1-2 句话交代背景，再抛出核心问题；
                如果概念较抽象，可先给出一个贴近工程的例子，再引导学习者分析其原理或边界。
                可视化与代码优先规则：
                - 只要问题涉及程序行为、API、配置、数据结构或实现方式，就必须在 stem 给出一段最小、真实、可运行或可推演的代码/配置，不能只做抽象描述；
                - 涉及调用链、生命周期、架构、状态流转、网络链路或图片式空间关系时，必须附一个 ```mermaid 图（flowchart 或 sequenceDiagram），节点文字简短；
                - 只有纯概念定义且代码和图都确实无助于理解时，才允许纯文字题干。
                题干用 Markdown 排版：涉及代码时用 ``` 代码块包裹并注明语言，关键术语用 **加粗**，
                必要时可用列表或 ### 小标题，让学习者阅读更清楚。
                一道题必须拆成「一个主问 + 可选的追问小问」，严禁把多个小问堆进一个题干：
                - stem 里只能放【一个】核心主问（一个问句、最多一个问号），
                  严禁用编号（1. 2. 3.）、"另外/此外/还有/以及"等把多个小问塞进 stem；
                - followups 可以是 0-2 条，不要求每题都生成。只有确实能巩固当前知识点、且难度不超过
                  当前层级时才生成；每条是一个独立问句，禁止重复主问、禁止换句话重问原题、禁止突然跳到偏门细节；
                - 追问只作为候选，老师必须在学习者完全回答当前问题或明确确认理解后才可能提出，
                  也可以提前停止，一个都不问。
                如果概念是算法 / 数据结构 / 代码实现类：请在题干里指定一道具体的 LeetCode 题目
                （题号 + 题名），并明确提示"先到 LeetCode 完成这道题，然后把通过全部用例的代码
                粘贴到输入框"；复杂度、边界、优化等更深的追问同样放 followups，留到作答后的对话中逐条进行。
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
                - 题干(stem)只放【一个】核心主问（一个问句、最多一个问号）；followups 为可选的 0-2 条，
                  不是必须生成。只保留符合当前认知层、确实有助于巩固且不重复主问的小问；
                  学习者完全答对或明确确认理解后才可能问下一条，也允许完全不追问。
                - 题干用 Markdown 排版：涉及代码时用 ``` 代码块包裹（注明语言），关键术语加粗，
                  可适当用列表或小标题，方便学习者阅读。
                - 非纯概念题必须给出最小代码/配置，而不是只描述“某段代码”；调用链、生命周期、架构、
                  状态流转或图片式关系必须再给一个 ```mermaid 围栏图，让前端直接渲染成示意图。
                - 如果题目涉及依赖分析、框架机制或分层设计，请直接给出具体代码/配置/链路背景，
                  然后问"你怎么看""这里有什么问题""为什么""会验证什么、不会验证什么"。
                - PRIMARY 概念是本题真正要推进的目标，评分点应覆盖其核心，3-5 条
                - ANCHOR 概念是用户已掌握的挂靠点，评分点只考它与 PRIMARY 的关系或边界，1-3 条，
                  不要再考它的基础定义
                - byConcept 必须为每个 conceptIndex 各出一组评分点

                以下是该知识点已出过的题干，新题必须在提问角度上明显不同，且不能再用"候选人/他/他们"叙事：
                %s
                """, conceptList, probeType, format, task.arity(), avoidBlock);

        // 学习上下文注入：学生进度 + 概念要点 + 用户资料块 + 互联网补充。
        // 原则：这些都是「素材」不是「天花板」——评分点以概念核心（通用知识）为主，
        // 资料/互联网细节为辅；严禁把通用知识冒充成“资料里说的”，也不得编造素材里没有的内容。
        if (contextText != null && !contextText.isBlank()) {
            user += "\n\n以下是本题的学习上下文（学生进度 / 概念要点 / 用户上传资料 / 互联网补充）：\n"
                    + contextText + "\n\n出题原则：\n"
                    + "- 用户上传资料覆盖到的细节：以资料为准、可考深（结合用户实际书/项目）；\n"
                    + "- 资料没覆盖的概念部分：用通用知识体系正常出题，但不得把通用知识冒充成“资料里说的”；\n"
                    + "- 参考学生当前进度出题：掌握度低从概念最基础的定义/最小示例开始，掌握度高才出进阶/综合题；\n"
                    + "- 严禁编造资料或互联网内容里没有的事实。";
        }

        return new Prompt(system, user);
    }

    public GeneratedQuestion generate(SelectedTask task, ProbeType probeType,
                                      ResponseFormat format, List<String> avoidStems,
                                      String contextText) {
        Prompt p = prompt(task, probeType, format, avoidStems, contextText);
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
