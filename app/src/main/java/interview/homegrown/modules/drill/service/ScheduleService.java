package interview.homegrown.modules.drill.service;

import interview.homegrown.modules.drill.domain.Grade;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 间隔排程（FSRS 简化四档）。接口按 FSRS 形状定，后续可换完整算法。
 * 计时 opt-in：不计时时 fail-safe 偏保守（间隔更短），保证复习密度。
 */
@Service
public class ScheduleService {

    public Instant nextDue(Grade grade, boolean timed) {
        int days = switch (grade) {
            case AGAIN -> 0;              // 当天重来
            case HARD  -> timed ? 1 : 1; // 吃力：保守 1 天
            case GOOD  -> timed ? 2 : 1; // 正常
            case EASY  -> timed ? 4 : 2; // 轻松（不计时封顶 2 天）
        };
        return Instant.now().plus(days, ChronoUnit.DAYS);
    }
}
