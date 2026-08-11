package interview.homegrown.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 开启 Spring 定时任务（每日 06:30 预生成今日学习任务）。 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
