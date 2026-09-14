-- 模拟面试暂停：paused_at 保存当前暂停起点，累计暂停秒数用于补偿截止时间。
ALTER TABLE interview_session ADD COLUMN IF NOT EXISTS paused_at TIMESTAMP;
ALTER TABLE interview_session ADD COLUMN IF NOT EXISTS total_paused_seconds BIGINT NOT NULL DEFAULT 0;
