package interview.homegrown.modules.drill.domain;

import java.util.Arrays;
import java.util.List;

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

    /** 给定 arity，返回所有合法的 probe_type（服务端确定性过滤，不交给 LLM） */
    public static List<ProbeType> forArity(int arity) {
        return Arrays.stream(values()).filter(p -> p.supports(arity)).toList();
    }
}
