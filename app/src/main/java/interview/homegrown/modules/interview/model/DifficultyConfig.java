package interview.homegrown.modules.interview.model;

/**
 * 面试难度配置：动态追问数量与深度。
 * <p>时间统一为 60 分钟（所有难度一致）；难度只决定追问数量与深度：
 *  初级追问 2 个（浅）、中级 3 个（中）、高级 4 个（深）。
 */
public record DifficultyConfig(int followUpCount) {

    public static final int BASE_QUESTION_COUNT = 6;      // 所有难度基础题数量一致
    public static final int UNIFIED_DURATION_MINUTES = 60; // 所有难度统一时长 60 分钟

    public static DifficultyConfig of(InterviewDifficulty difficulty) {
        return switch (difficulty) {
            case JUNIOR -> new DifficultyConfig(2);
            case MIDDLE -> new DifficultyConfig(3);
            case SENIOR -> new DifficultyConfig(4);
        };
    }

    public String durationText() {
        return UNIFIED_DURATION_MINUTES + " 分钟";
    }
}
