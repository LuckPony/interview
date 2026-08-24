-- 模拟面试重构：会话计时与动态追问
-- start_at：面试开始时间（用于 60 分钟硬上限 / 难度时长约束）
-- duration_min：本次面试计划时长（由难度决定：初 18-24 / 中 30-40 / 高 48-60）
ALTER TABLE interview_session ADD COLUMN IF NOT EXISTS start_at TIMESTAMP;
ALTER TABLE interview_session ADD COLUMN IF NOT EXISTS duration_min INTEGER;
