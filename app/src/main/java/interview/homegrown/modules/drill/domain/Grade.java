package interview.homegrown.modules.drill.domain;

/** FSRS 复习档位。服务端据此计算 next due_at，不委托 LLM。 */
public enum Grade {
    AGAIN,  // 没掌握，立即重来
    HARD,   // 吃力
    GOOD,   // 正常
    EASY    // 轻松（仅当计时 opt-in 时给最宽间隔，否则 fail-safe 保守）
}
