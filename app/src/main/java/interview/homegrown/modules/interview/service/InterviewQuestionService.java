package interview.homegrown.modules.interview.service;

import interview.homegrown.common.ai.StructuredOutputInvoker;
import interview.homegrown.modules.interview.model.DifficultyConfig;
import interview.homegrown.modules.interview.model.InterviewDifficulty;
import interview.homegrown.modules.interview.model.InterviewQuestionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 面试出题服务
 * 依据 = 简历（70% 权重）+ 学习方向知识点（30% 权重），可只选其一。
 *
 * <p>所有轮次都预出 {@link DifficultyConfig#BASE_QUESTION_COUNT} 道<b>主问题</b>，
 * 第 1 题固定为「请介绍一下你自己」（不追问）；其余基础题结合简历/学习方向由 LLM 生成，
 * 一面验证项目落地，二面考察技术原理，三面考察架构与治理。追问不预生成，由回答动态决定。</p>
 */
@Service
public class InterviewQuestionService {

    private static final Logger log = LoggerFactory.getLogger(InterviewQuestionService.class);

    private static final String SELF_INTRO = "请先简单介绍一下你自己，包括你的技术栈和项目经历。";

    private final StructuredOutputInvoker invoker;
    private final InterviewSkillService skillService;

    public InterviewQuestionService(StructuredOutputInvoker invoker, InterviewSkillService skillService) {
        this.invoker = invoker;
        this.skillService = skillService;
    }

    private static final String SYSTEM_PROMPT = """
            你是一位资深的技术面试官，擅长针对不同技术方向设计高质量的面试题。
            请输出 JSON 格式的题目列表，每个题目只包含 question 字段（主问题），followups 返回空数组。
            """;

    /**
     * 生成面试基础题（数量 = DifficultyConfig.BASE_QUESTION_COUNT）
     *
     * @param skillName      面试方向名称（可空：方向由简历/学习方向决定）
     * @param difficulty     兼容字段，业务含义为一面 / 二面 / 三面
     * @param resumeText     简历文本（可空；非空时权重 70%）
     * @param planConcepts   学习方向知识点（可空；非空时权重 30%）
     * @param mixed          简历与学习方向是否都提供（都提供时提示 LLM 按 70/30 占比）
     * @param provider       LLM Provider
     */
    public InterviewQuestionResult generateBaseQuestions(String skillName,
                                                         InterviewDifficulty difficulty,
                                                         String resumeText,
                                                         List<String> planConcepts,
                                                         boolean mixed,
                                                         String provider, String corpusReference) {

        int total = DifficultyConfig.BASE_QUESTION_COUNT;
        int toGenerate = total - 1; // 第 1 题自我介绍由代码固定
        DifficultyConfig cfg = DifficultyConfig.of(difficulty);
        boolean hasResume = resumeText != null && !resumeText.isBlank();
        boolean hasPlan = planConcepts != null && !planConcepts.isEmpty();
        boolean hasCorpus = corpusReference != null && !corpusReference.isBlank();

        String direction = (skillName != null && !skillName.isBlank())
                ? skillName
                : (hasCorpus ? "所选知识库资料" : hasPlan ? "学习方向知识点" : "候选人简历相关技术栈");

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("面试方向: ").append(direction).append("\n");
        userPrompt.append("面试轮次：").append(cfg.roundName()).append("\n");
        userPrompt.append("本轮核心：").append(cfg.focus()).append("\n");
        userPrompt.append("本场总计固定 8 道主问题，第 1 道自我介绍已由系统提供。请严格生成剩余 ")
                .append(toGenerate).append(" 道。\n\n");
        userPrompt.append("在内部先盘点简历中的项目、职责、关键功能、技术栈和可量化结果，再按下面的槽位出题；不要输出盘点过程。\n");
        userPrompt.append(coverageBlueprint(difficulty, hasResume));
        userPrompt.append("""

                出题硬性规则：
                1. 严格按 7 个槽位各出 1 题，不得把问题集中在单一项目、单一技术或同一知识域。
                2. 输入中有多个项目或章节时至少覆盖 2 个；同一项目/章节最多 2 道，同一具体技术点最多 1 道。
                3. 必须引用输入材料中的真实项目名、功能、技术、职责或知识点来提问；有简历时必须紧扣简历，禁止脱离材料随机出八股题。
                4. 每题只考察一个清晰主题，但应要求候选人说清事实、过程、依据或取舍，不能只用“谈谈你的理解”。
                5. 七题合起来要覆盖项目、核心技术栈、数据/并发、可靠性/测试、决策权衡等不同维度；材料确实没有某类内容时，换成另一项未覆盖的真实内容。
                6. 题目从材料中可确认的内容出发，不替候选人编造经历、指标和实现。
                7. 每个问题只输出 question 字段，followups 必须为空数组；不要提前生成追问。
                """);

        if (hasResume) {
            userPrompt.append("\n候选人简历内容");
            userPrompt.append(mixed && corpusReference == null ? "（出题权重 70%，应重点围绕简历中的项目与技术栈）" : "（出题依据）");
            userPrompt.append("：\n------------------------------------------\n");
            userPrompt.append(resumeText).append("\n");
            userPrompt.append("------------------------------------------\n");
        }
        if (hasPlan) {
            userPrompt.append("\n候选人学习方向知识点");
            userPrompt.append(mixed && corpusReference == null ? "（出题权重 30%，作为补充考察范围）" : "（出题依据）");
            userPrompt.append("：\n");
            for (String c : planConcepts) {
                userPrompt.append("- ").append(c).append("\n");
            }
        }

        if (hasCorpus) {
            userPrompt.append("\n【知识库出题范围】\n").append(corpusReference)
                .append("\n本场必须以这份资料的知识点为主要技术范围，同时优先寻找它与简历项目/学习方向的交集。各题覆盖不同章节或知识域，不能连续围绕同一段内容；每道技术题末尾标注依据的 [来源 S序号]（无章节索引时注明原文抽样）。资料不足时明确说明，不编造原文。\n");
        }
        InterviewQuestionResult result = invoker.invoke(
                SYSTEM_PROMPT,
                userPrompt.toString(),
                InterviewQuestionResult.class,
                provider
        );

        // 组装：第 1 题固定自我介绍 + 7 道互不重复的主问题。
        List<InterviewQuestionResult.InterviewQuestion> questions = new ArrayList<>();
        questions.add(new InterviewQuestionResult.InterviewQuestion(SELF_INTRO, List.of()));
        Set<String> seen = new HashSet<>();
        seen.add(normalizeQuestion(SELF_INTRO));
        for (InterviewQuestionResult.InterviewQuestion q : result.questions()) {
            if (questions.size() >= total) break;
            if (q.question() == null || q.question().isBlank()) continue;
            if (!seen.add(normalizeQuestion(q.question()))) continue;
            questions.add(new InterviewQuestionResult.InterviewQuestion(q.question(), List.of()));
        }

        // LLM 数量不足或产生重复题时，用不同能力维度的题目补齐，避免重复兜底造成偏科。
        for (String fallback : fallbackQuestions(difficulty, direction, hasResume)) {
            if (questions.size() >= total) break;
            if (!seen.add(normalizeQuestion(fallback))) continue;
            questions.add(new InterviewQuestionResult.InterviewQuestion(fallback, List.of()));
        }

        log.info("基础题生成完成: direction={}, difficulty={}, 题数={}", direction, difficulty, questions.size());
        return new InterviewQuestionResult(questions);
    }

    private String coverageBlueprint(InterviewDifficulty difficulty, boolean hasResume) {
        if (!hasResume) {
            return """
                    七个覆盖槽位：
                    1. 材料中的核心目标或核心概念
                    2. 一个关键流程及其具体实现
                    3. 一个主要技术原理
                    4. 数据、状态或并发相关知识
                    5. 异常、边界与可靠性
                    6. 方案选择、对比与权衡
                    7. 综合应用、排错或优化
                    """;
        }
        return switch (difficulty) {
            case JUNIOR -> """
                    一面七个覆盖槽位（侧重“确实做过、具体怎么做”）：
                    1. 选择一个代表项目，追问业务目标、个人职责和最终结果
                    2. 选择一个核心功能，让候选人按调用/数据链路说明完整实现过程
                    3. 选择一项具体操作，追问代码或组件层面是怎么实现的
                    4. 换一个项目或模块，核实候选人的真实贡献和协作边界
                    5. 针对一项技术选型，追问为什么这样做、替代方案是什么
                    6. 追问测试、异常处理、排错或上线过程中遇到的真实问题
                    7. 追问一次优化、改造或结果验证，要求说明前后变化
                    """;
            case MIDDLE -> """
                    二面七个覆盖槽位（侧重“项目用到的知识点是否真正掌握”）：
                    1. 从核心框架或语言特性中选一个，考察底层机制与调用链
                    2. 从数据库、索引或事务中选一个，考察原理、隔离与边界
                    3. 从缓存、消息、异步或并发中选一个，考察一致性和失败处理
                    4. 从接口、网络、安全或鉴权中选一个，考察协议与风险
                    5. 从项目关键组件中选一个，对比替代方案及选型权衡
                    6. 从测试、可观测性、性能或故障排查中选一个，考察工程能力
                    7. 给出一个与简历项目贴合的变化场景，考察知识迁移与方案设计
                    """;
            case SENIOR -> """
                    三面七个覆盖槽位（侧重“能否负责复杂系统并推动演进”）：
                    1. 系统架构、模块边界及关键链路
                    2. 容量评估、性能瓶颈与扩展方案
                    3. 高可用、降级、重试、幂等与故障恢复
                    4. 数据一致性、并发冲突或分布式取舍
                    5. 安全、可观测性与生产问题定位
                    6. 一项重要技术决策的约束、替代方案和长期成本
                    7. 面向业务增长或团队协作的系统演进与落地优先级
                    """;
        };
    }

    private List<String> fallbackQuestions(InterviewDifficulty difficulty, String direction, boolean hasResume) {
        String scope = hasResume ? "你简历中最有代表性的项目" : direction;
        return switch (difficulty) {
            case JUNIOR -> List.of(
                    "请选择" + scope + "，说明业务目标、你的职责以及最终交付结果。",
                    "请沿着一次真实请求或数据流，完整说明核心功能从入口到落库的实现过程。",
                    "项目中最关键的一项具体操作是如何编码实现的？请说明使用的组件和关键步骤。",
                    "请换一个项目或模块，说明其中由你独立完成的部分以及与他人的协作边界。",
                    "请选择一个主要技术方案，说明当时为什么采用它，以及没有采用什么替代方案。",
                    "你在测试、联调或上线时遇到过什么真实问题？当时如何定位并解决？",
                    "请选择一次优化或重构，说明改动前的问题、具体措施和结果如何验证。");
            case MIDDLE -> List.of(
                    "请选择项目使用最深入的框架能力，说明它的底层机制以及一次完整调用链。",
                    "项目的数据表、索引和事务如何设计？请说明一个边界条件下的处理方式。",
                    "项目涉及缓存、异步或并发时，如何处理一致性、重复执行和失败重试？",
                    "项目的接口鉴权与数据安全如何实现？主要攻击面和防护边界是什么？",
                    "请选择一个关键组件与可替代方案比较，说明取舍依据和适用边界。",
                    "项目如何进行测试、监控和性能分析？请结合一次真实排障过程说明。",
                    "如果当前流量或数据量增长十倍，你会优先改造哪些环节，为什么？");
            case SENIOR -> List.of(
                    "请画出或口述系统的核心架构、模块边界与关键请求链路，并说明这样拆分的原因。",
                    "系统的容量上限和主要性能瓶颈在哪里？你会如何评估并逐步扩展？",
                    "请说明系统在依赖超时、节点故障和重复请求下如何保证可用性与可恢复性。",
                    "项目中最难的数据一致性或并发问题是什么？请说明约束和最终取舍。",
                    "生产环境如何建立日志、指标、链路追踪与告警，并完成一次故障定位？",
                    "请选择一个重要架构决策，说明替代方案、长期成本以及什么情况下会推翻它。",
                    "如果业务和团队规模继续增长，你会如何安排未来两个阶段的系统演进优先级？");
        };
    }

    private String normalizeQuestion(String question) {
        return question.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

}
