package interview.homegrown.modules.drill.service;

import interview.homegrown.modules.drill.domain.Grade;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 间隔排程（FSRS 简化）。正向：档位越高复习间隔越长；出现 AGAIN 后跨档降级。
 * 计时 opt-in：不计时时 fail-safe 偏保守（间隔更短），保证复习密度。
 */
@Service
public class ScheduleService {

    public Instant nextDue(Grade grade, boolean timed) {
        return nextDue(grade, timed, false, 0, false);
    }

    /**
     * 基于苏格拉底两级评分的动态到期时间。
     * <p>规则（已与用户确认）：
     * <ul>
     *   <li>finalGrade 决定基准间隔：GOOD/EASY 长，HARD 短，AGAIN 当天重来；</li>
     *   <li>看过答案（revealed）→ 强制当天重来（未独立掌握）；</li>
     *   <li>经过引导（guided）→ 说明未独立掌握，间隔缩短（引导轮数越多越短）。</li>
     * </ul>
     *
     * @param finalGrade  最终档位（G1 或 G2）
     * @param timed       是否开启计时（不计时偏保守）
     * @param guided      是否经过苏格拉底引导
     * @param guideRounds 引导轮数
     * @param revealed    是否看过答案
     */
    public Instant nextDue(Grade finalGrade, boolean timed, boolean guided, int guideRounds, boolean revealed) {
        if (revealed) {
            return Instant.now().plus(0, ChronoUnit.DAYS);   // 看过答案：当天重来
        }
        int base = switch (finalGrade) {
            case AGAIN -> 0;               // 当天重来
            case HARD -> timed ? 1 : 1;    // 吃力：保守 1 天
            case GOOD -> guided ? Math.max(1, 2 - Math.min(guideRounds, 1)) : (timed ? 2 : 1); // 引导过则缩短
            case EASY -> timed ? 4 : 2;    // 轻松
        };
        return Instant.now().plus(base, ChronoUnit.DAYS);
    }
}