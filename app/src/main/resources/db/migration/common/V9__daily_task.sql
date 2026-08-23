-- 每日学习任务：每个学习方向每天「复习 + 新学」的自动排期。
--
-- 由 DailyPlanService 生成：
--   REVIEW = 到期（dueAt<=now）且已掌握的概念，按 dueAt 升序，封顶 8
--   NEW    = 本方向未学概念按 layer 升序，封顶 3
-- 落表后异步预生成题目（question_id），状态 PENDING -> READY；
-- 用户在「今日任务」点开即以预生成题开 run，不等待 LLM。
CREATE TABLE IF NOT EXISTS daily_task (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    plan_id     BIGINT,                       -- 所属学习方向（可空=全局）
    task_date   DATE         NOT NULL,        -- 哪一天的安排
    kind        VARCHAR(8)   NOT NULL,        -- REVIEW / NEW
    concept_id  BIGINT       NOT NULL,
    status      VARCHAR(12)  NOT NULL DEFAULT 'PENDING',  -- PENDING/READY/DONE/SKIPPED
    question_id BIGINT,                       -- 预生成好的题（READY 后非空）
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_daily_task_user_date ON daily_task (user_id, task_date);
-- 幂等防重：同一用户同一天同一任务只生成一次（REVIEW 与 NEW 概念集互斥）
CREATE UNIQUE INDEX IF NOT EXISTS uni_daily_task_uid_date_kind_concept
    ON daily_task (user_id, task_date, kind, concept_id);
