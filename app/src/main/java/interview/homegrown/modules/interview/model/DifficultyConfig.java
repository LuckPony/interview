package interview.homegrown.modules.interview.model;

/**
 * 面试轮次配置。
 *
 * <p>数据库继续使用 JUNIOR / MIDDLE / SENIOR 兼容已有会话，业务含义调整为
 * 一面 / 二面 / 三面。每轮固定 8 道主问题，追问由回答质量动态决定，最多 2 次。</p>
 */
public record DifficultyConfig(String roundName, String focus, int maxFollowUpCount) {

    public static final int BASE_QUESTION_COUNT = 8;
    public static final int MAX_FOLLOW_UP_COUNT = 2;
    public static final int UNIFIED_DURATION_MINUTES = 60;

    public static DifficultyConfig of(InterviewDifficulty difficulty) {
        return switch (difficulty) {
            case JUNIOR -> new DifficultyConfig("一面", "项目真实性、实际贡献与具体实现", MAX_FOLLOW_UP_COUNT);
            case MIDDLE -> new DifficultyConfig("二面", "项目技术栈背后的原理、边界与工程权衡", MAX_FOLLOW_UP_COUNT);
            case SENIOR -> new DifficultyConfig("三面", "架构设计、稳定性、性能治理与系统演进", MAX_FOLLOW_UP_COUNT);
        };
    }

    public String durationText() {
        return UNIFIED_DURATION_MINUTES + " 分钟";
    }
}
