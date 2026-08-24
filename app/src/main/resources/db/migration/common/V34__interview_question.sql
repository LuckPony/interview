-- 模拟面试：题目持久化（面试会话题目列表），替代进程内缓存 —— 后端重启/桌面端重开不丢
CREATE TABLE IF NOT EXISTS interview_question (
    session_id     VARCHAR(36) PRIMARY KEY REFERENCES interview_session(id) ON DELETE CASCADE,
    questions_json TEXT NOT NULL,
    created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
