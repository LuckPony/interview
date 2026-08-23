-- 子知识点完成状态必须独立于大知识点 mastery。
-- run 记录本次「先教后考」实际聚焦的子点；只有该 run 完成评分后，该子点才算完成。
ALTER TABLE drill_run ADD COLUMN IF NOT EXISTS focus_sub_point VARCHAR(300);
CREATE INDEX IF NOT EXISTS idx_drill_run_user_focus_subpoint
    ON drill_run (user_id, focus_sub_point, status);
