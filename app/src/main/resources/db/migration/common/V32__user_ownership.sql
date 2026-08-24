-- 用户数据隔离：面试会话与简历按用户归属
-- （此前两者均未关联 user_id，任意用户可见/操作彼此的数据）
ALTER TABLE interview_session ADD COLUMN IF NOT EXISTS user_id BIGINT;
ALTER TABLE resume ADD COLUMN IF NOT EXISTS user_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_interview_session_user ON interview_session(user_id);
CREATE INDEX IF NOT EXISTS idx_resume_user ON resume(user_id);

-- 存量数据回填：无归属的历史简历/会话归给最早注册的用户（id 最小）
UPDATE resume SET user_id = (SELECT MIN(id) FROM app_user) WHERE user_id IS NULL;
UPDATE interview_session SET user_id = (SELECT MIN(id) FROM app_user) WHERE user_id IS NULL;
