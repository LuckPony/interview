-- 模拟面试：会话表新增面试方式（TEXT/VOICE）与关联学习方向（逗号分隔，可多选）
ALTER TABLE interview_session ADD COLUMN IF NOT EXISTS mode VARCHAR(10) DEFAULT 'TEXT';
ALTER TABLE interview_session ADD COLUMN IF NOT EXISTS plan_ids VARCHAR(255);
