-- 学习计划关联导入的项目（用于项目导入→学习计划流程）
ALTER TABLE study_plan ADD COLUMN project_import_id BIGINT REFERENCES project_import(id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_study_plan_project_import ON study_plan (project_import_id);