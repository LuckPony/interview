-- V4__study_plan.sql
-- 痛点 1（破除信息茧房）：学习计划由「多轮对话动态生成」，不再写死成固定种子目录。
-- 用户自建学习方向（study_plan），对话把方向拆成带层级的知识点（concept.study_plan_id）。

-- ============ 学习方向：study_plan ============
CREATE TABLE IF NOT EXISTS study_plan (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    title       VARCHAR(120) NOT NULL,          -- 方向名，如 "前端" / "微服务"
    goal        TEXT,                            -- 对话提炼出的学习目标
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- 同一用户同名方向只建一份（confirm 幂等，避免双击重复建）
    CONSTRAINT uni_study_plan_user_title UNIQUE (user_id, title)
);
CREATE INDEX IF NOT EXISTS idx_study_plan_user ON study_plan (user_id);

-- updated_at 由实体侧 @UpdateTimestamp 维护（H2 无 plpgsql 触发器）。

-- ============ concept 挂到方向：study_plan_id（可空，旧种子概念不强制归属某方向） ============
ALTER TABLE concept ADD COLUMN IF NOT EXISTS study_plan_id BIGINT REFERENCES study_plan(id);
CREATE INDEX IF NOT EXISTS idx_concept_plan ON concept (study_plan_id);
