package interview.homegrown.modules.interview.model;

/**
 * 面试难度配置：时长范围（分钟）与动态追问数量。
 * 面试时长由难度决定（提示词约束基础题量与之匹配）：
 *  初级 18-24 分钟、追问 2 个（难度浅、数量少）
 *  中级 30-40 分钟、追问 3 个（有一定深度）
 *  高级 48-60 分钟、追问 4 个（非常深、贴近真实掌握度考察）
 */
public record DifficultyConfig(int minMinutes, int maxMinutes, int followUpCount) {

    public static final int BASE_QUESTION_COUNT = 6;   // 所有难度基础题数量一致
    public static final int MAX_INTERVIEW_MINUTES = 60; // 计时硬上限

    public static DifficultyConfig of(InterviewDifficulty difficulty) {
        return switch (difficulty) {
            case JUNIOR -> new DifficultyConfig(18, 24, 2);
            case MIDDLE -> new DifficultyConfig(30, 40, 3);
            case SENIOR -> new DifficultyConfig(48, 60, 4);
        };
    }

    public String durationText() {
        return minMinutes + "-" + maxMinutes + "分钟";
    }
}
