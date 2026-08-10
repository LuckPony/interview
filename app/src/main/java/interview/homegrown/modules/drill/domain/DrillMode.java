package interview.homegrown.modules.drill.domain;

/** 作答场景维度，与计时档正交。 */
public enum DrillMode {
    LEARN,       // 学习模式（开卷可选、opt-in 计时、可撤回）
    REHEARSAL    // 模拟面试（闭卷、COUNTDOWN、不可编辑、追问封顶 2 轮）
}
