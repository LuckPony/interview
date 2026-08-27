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
     * @param format 作答判分维度（ResponseFormat）。当前恒为 FREE_TEXT，不注入出题 prompt；
     *               出题形态由 {@link ProbeType#formHint()} 决定，故此处参数保留但不参与题干文本。
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
                【题干明确性硬约束】题干必须在第一遍读完就能让学习者准确知道：
                1. 要做什么：用明确指令动词开头（"解释""写出""对比""设计""分析""实现""修复""给出"），
                   不要用模糊的"谈谈""说说""聊聊""你怎么想"。若需要多个动作，用最简洁方式列清每个动作的边界；
                2. 做到什么程度：说清范围与深度。是"给出一段可运行的最小代码"还是"只解释关键原理"，是"列出全部
                   要点"还是"挑最关键的一两个"，都要在题干里点明，避免学习者答偏或答多；
                3. 输出形式：需要代码就给明确提示（如"用 ```python 写出"），需要分点就给"请分点列出"，
                   需要画图就给"画出调用链（可用 mermaid 或其他方式）"。不要留白让学习者猜格式；
                4. 一句话只表达一个目的，主问的意图要单一、无歧义。严禁出现"简单说下大概和核心区别"这类
                   一题多问、程度模糊、边界不清的表述；也不要让学习者去猜"这里到底要我答哪个"。
                反例（应避免）："请简述一下你对这个的理解和它的一些特点和它的好处"——
                这是把"概念解释 + 特点罗列 + 价值评价"混成一团、且程度词（简述/理解）模糊的题干。
                正例："请对比 A 与 B 在 X 场景下的差异，分点列出其中 3 个最核心的差异，并各用一句白话解释。"
                题干允许 1-2 句背景铺垫，但背景必须和主问明确区分（可用序号、加粗或"题目："引导），
                不能把背景说成要求、让学习者误以为背景也是要回答的内容。
                可视化规则（管题干呈现，不等同于作答形态）：
                - 仅当概念属于「代码相关领域」且问题涉及程序行为、API、配置、数据结构或实现方式时，才在 stem 给出一段最小、真实、可运行或可推演的代码/配置作为背景，不能只做抽象描述；
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
                【作答形态：服从认知动作 + 领域感知】不要把所有题都出成"写代码"。
                作答形态由两件事共同决定——本题的「认知动作类型」（见下方出题要求里的作答形态指引）和概念的领域性质：
                - 领域判断：先看概念的主题与说明。只有属于「代码相关领域」（编程语言、算法、数据结构、
                  并发、数据库、网络、操作系统、框架、脚本、命令行、配置、DevOps 等）才允许出现
                  代码/命令/配置类题干与代码类作答。
                - 非代码领域（产品、管理、法律、历史、生物、金融、营销、医学、写作、统计等）：
                  严禁出"写代码"题，也严禁在题干里硬塞代码；一律用文字解释、分点列表、对比表格、
                  mermaid 图、场景决策等形式考察。
                - 代码相关领域也只在该认知动作确实需要时才写代码：TRAP 出"找 bug / 纠错"，
                  SCENARIO 出"场景设计 / 选型决策"，RECALL 出"解释定义原理"，CLOZE 出"补全关键片段"，
                  REVERSE 出"倒推原因"，CONTRAST 出"对比方案"，INTEGRATION 出"端到端设计"。
                  不是每种题型都要学生从零写一整段代码——解释、设计、纠错、对比同样重要，请严格按下方
                  作答形态指引出题，不要一律写成"实现/写出代码"。
                只产出题目与评分点，严禁给出答案、解析或提示。
                评分点(points)必须是可客观核验的知识点，每条带权重 weight(1-3，越核心越大)。
                【评分范围硬约束】：points 只能覆盖 stem 明确要求学生回答的内容。判断标准是：学习者只看 stem，
                就能知道该评分点需要作答。不能因为属于同一概念，就把没有出现在 stem 中的方法、API、边界、场景、
                优缺点或 followups 内容塞进 points。followups 仅用于后续教学追问，不纳入本题量化评分；即使后续真的问到，
                也只用于帮助完善对主问的理解，不能预先扩张主问评分范围。
                例如 stem 只要求“用 set 去重并解释为什么”，points 只能考 set 转换代码、元素唯一性及与输出顺序直接相关的内容，
                不能考 add/remove/discard、成员判断、集合运算等未要求内容。
                若题干用"分点列出 N 个""挑出最关键的一两个""只写一处"等收窄表述，points 的数量与范围必须与之
                精确对应——绝不能题干说 3 点、评分点却考 5 点，或题干说只写关键、评分点却要求面面俱到；
                否则学习者按题干作答会被判分器错判为"缺漏"，直接用反"题干明确性"。
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
                - 作答形态指引：%s
                - 概念数 arity：%d（必须恰好覆盖上面全部概念，不得增删）
                - 题干(stem)必须是面试官/老师直接向学习者本人提问：
                  构造场景、抛出设计问题、追问边界情况、或让学习者解释一个真实会遇到的难题。
                  禁止"你跟候选人讨论时，他提出..."这种让学习者评判第三方的写法。
                - 题干(stem)只放【一个】核心主问（一个问句、最多一个问号）；followups 为可选的 0-2 条，
                  不是必须生成。只保留符合当前认知层、确实有助于巩固且不重复主问的小问；
                  学习者完全答对或明确确认理解后才可能问下一条，也允许完全不追问。
                - 【题干明确性】题干要自包含、无歧义：用明确指令动词开头（解释/写出/对比/设计/分析/实现/给出），
                  说清"做到什么程度"（是最小代码还是关键原理、是分点列出全部还是挑最短一两个）与"输出形式"
                  （```代码 / 分点列表 / mermaid 图），一句话只表达一个目的；背景与主问用加粗或"题目："明确分开，
                  方便学习者一眼看清"我要回答什么"。禁止"简单说下大概和核心区别"这类一题多问、程度模糊的表述。
                - 题干用 Markdown 排版：涉及代码时用 ``` 代码块包裹（注明语言），关键术语加粗，
                  可适当用列表或小标题，方便学习者阅读。
                - 仅当概念属于「代码相关领域」且题干确实需要时，才给出最小代码/配置作为背景，而不是只描述
                   “某段代码”；调用链、生命周期、架构、状态流转或图片式关系（代码与非代码领域都适用）
                   必须再给一个 ```mermaid 围栏图，让前端直接渲染成示意图。非代码领域严禁硬塞代码。
                - 仅当题目涉及代码相关领域的依赖分析、框架机制或分层设计时，才给出具体代码/配置/链路背景，
                   然后问"你怎么看""这里有什么问题""为什么""会验证什么、不会验证什么"。
                - PRIMARY 概念是本题真正要推进的目标，评分点应覆盖 stem 明确要求回答的核心，通常 2-4 条；
                  每一条都必须能在 stem 中找到直接对应的要求，不得覆盖尚未提出的 followups
                - ANCHOR 概念只有在 stem 明确要求说明它与 PRIMARY 的关系或边界时才能设置评分点，0-2 条；
                  不要考它的基础定义，也不要仅因它出现在概念清单中就强行增加评分点
                - byConcept 必须为每个 conceptIndex 各出一组评分点

                以下是该知识点已出过的题干，新题必须在提问角度上明显不同，且不能再用"候选人/他/他们"叙事：
                %s
                """, conceptList, probeType, probeType.formHint(), task.arity(), avoidBlock);

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
