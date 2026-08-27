package interview.homegrown.modules.drill.domain;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 认知动作维度（四维签名之一）。
 * arity（参与概念数）不是类别而是字段：每种 probe_type 只声明它允许的取值范围。
 * 修订 8：MCQ / SPEAK 不在此枚举内，它们分别属于 response_format / answer_mode。
 */
public enum ProbeType {

    /** 直接回忆定义与要点 */
    RECALL(1, 1),
    /** 挖空填补关键环节 */
    CLOZE(1, 1),
    /** 给结论倒推机制/给现象倒推原因 */
    REVERSE(1, 2),
    /** 埋一个常见误解，看是否踩坑 */
    TRAP(1, 3),
    /** 放进真实场景做决策 */
    SCENARIO(1, 3),
    /** 对比两者差异与适用边界（天然需要 >=2 个概念） */
    CONTRAST(2, 3),
    /** 把多个概念串成一条完整链路 */
    INTEGRATION(2, 3);

    private final int minArity;
    private final int maxArity;

    ProbeType(int minArity, int maxArity) {
        this.minArity = minArity;
        this.maxArity = maxArity;
    }

    public int minArity() {
        return minArity;
    }

    public int maxArity() {
        return maxArity;
    }

    public boolean supports(int arity) {
        return arity >= minArity && arity <= maxArity;
    }

    /**
     * 已停用出题的题型（保留枚举值兼容历史题库数据与渲染，但不再生成新题）。
     * <p>CLOZE 挖空题：要求"题干声明 / 代码挖空 / 题目复述"三处对账，模型规划-渲染漂移导致
     * 高比例的自相矛盾题，且无通用可靠的服务端校验能兜住，苏格拉底教学改版中先行停用。
     */
    private static final Set<ProbeType> DISABLED = EnumSet.of(CLOZE);

    /** 给定 arity，返回所有合法且仍在用的 probe_type（服务端确定性过滤，不交给 LLM） */
    public static List<ProbeType> forArity(int arity) {
        return Arrays.stream(values())
                .filter(p -> p.supports(arity))
                .filter(p -> !DISABLED.contains(p))
                .toList();
    }

    /**
     * 该认知动作对应的「作答形态」指引，注入出题 prompt。
     * 目的：让「写代码 / 解释 / 设计 / 纠错 / 对比」按题型合理分布，而不是把每一种认知动作都压成“写代码”。
     * 每个指引都同时给出「代码相关领域」与「非代码领域」的差异化出法，保证非编程学科也能被正确考察。
     */
    public String formHint() {
        return switch (this) {
            case RECALL -> "回忆定义与要点：代码相关领域解释概念原理/关键机制（可给最小代码示例辅助，但主问是“解释/定义”而非“写代码”）；非代码领域口头解释概念或列要点";
            case CLOZE -> "挖空补全关键环节：代码相关领域给一段代码/命令，挖掉关键片段让学习者补全；非代码领域给一段流程/公式/论述，挖掉关键步骤或术语补全";
            case REVERSE -> "给现象倒推原因/机制：代码相关领域给一段代码的运行现象或输出，让学习者倒推为什么；非代码领域给现象/结果，倒推背后原因或机制";
            case TRAP -> "纠错/排错：代码相关领域给一段有 bug 或有隐患的代码，让学习者找出并修复（bug 检查题）；非代码领域给一个常见错误结论或做法，让学习者指出并纠正";
            case SCENARIO -> "场景决策/设计：代码相关领域给一个工程场景，让学习者设计方案/选型并说明理由（设计题，主问是设计决策而非从零写码）；非代码领域给业务/生活场景，让学习者决策并说明理由";
            case CONTRAST -> "对比差异与边界：代码相关领域对比两种实现/方案/API 的取舍（可附最小代码对比，但主问是“对比”）；非代码领域对比两个概念/方法的差异与适用场景";
            case INTEGRATION -> "串联链路/端到端设计：代码相关领域给一个需串联多个概念的端到端设计/实现题（强调链路与集成，可要求关键代码片段）；非代码领域把多个概念串成完整流程或方案论述";
        };
    }
}
