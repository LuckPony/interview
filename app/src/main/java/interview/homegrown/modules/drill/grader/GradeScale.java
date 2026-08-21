package interview.homegrown.modules.drill.grader;

import interview.homegrown.modules.drill.domain.Grade;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 分数计算与档位映射，所有 Grader 共用一套，避免各实现各算一套导致画像不可比。
 * 这是"分数由服务端算，不交给 LLM"的具体承载。
 */
public final class GradeScale {

    /** 及格线，唯一定义处。判分、REHEARSAL 逐轮通过、内化债务统计共用同一条线 */
    public static final BigDecimal PASS_LINE = BigDecimal.valueOf(60);

    private GradeScale() {
    }

    /** 等权命中率：HIT=1，PARTIAL=0.5，MISS=0，映射到 0-100；NA（未考察）不计入分子分母 */
    public static BigDecimal score(List<PointVerdict> results) {
        if (results == null || results.isEmpty()) {
            return BigDecimal.ZERO;
        }
        double sum = 0;
        int scored = 0;
        for (PointVerdict r : results) {
            if (isNotApplicable(r.verdict())) continue; // 未考察：不参与计分
            sum += weightOf(r.verdict());
            scored++;
        }
        if (scored == 0) return BigDecimal.ZERO; // 全部未考察（理论不会：主问必考）
        return BigDecimal.valueOf(sum / scored * 100).setScale(2, RoundingMode.HALF_UP);
    }

    private static boolean isNotApplicable(String verdict) {
        return verdict != null && "NA".equalsIgnoreCase(verdict.trim());
    }

    private static double weightOf(String verdict) {
        if (verdict == null) return 0.0;
        return switch (verdict.toUpperCase()) {
            case "HIT" -> 1.0;
            case "PARTIAL" -> 0.5;
            default -> 0.0;
        };
    }

    /**
     * 分数 -> FSRS 档位。
     * EASY 仅在用户主动开启计时时才可能拿到（opt-in fail-safe）：
     * 没计时就不知道他是不是翻着资料慢慢写的，于是封顶 GOOD，让间隔偏保守。
     */
    public static Grade toGrade(BigDecimal raw, boolean timed) {
        double v = raw == null ? 0 : raw.doubleValue();
        if (v >= 85) return timed ? Grade.EASY : Grade.GOOD;
        if (v >= 60) return Grade.GOOD;
        if (v >= 30) return Grade.HARD;
        return Grade.AGAIN;
    }

    /** content 是否通过（REHEARSAL 逐轮判定用） */
    public static boolean passed(BigDecimal raw) {
        return raw != null && raw.compareTo(PASS_LINE) >= 0;
    }
}
