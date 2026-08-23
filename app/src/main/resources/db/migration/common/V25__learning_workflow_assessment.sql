-- 统一学习工作流：记录普通子点练习 / 大知识点综合检测 / 层级综合检测。
ALTER TABLE drill_run ADD COLUMN IF NOT EXISTS purpose VARCHAR(32) NOT NULL DEFAULT 'SUB_POINT_PRACTICE';
ALTER TABLE drill_run ADD COLUMN IF NOT EXISTS plan_id BIGINT;
ALTER TABLE drill_run ADD COLUMN IF NOT EXISTS assessment_concept_id BIGINT;
ALTER TABLE drill_run ADD COLUMN IF NOT EXISTS assessment_layer INTEGER;

CREATE INDEX IF NOT EXISTS idx_drill_run_assessment
    ON drill_run (user_id, plan_id, purpose, assessment_concept_id, assessment_layer, status);
