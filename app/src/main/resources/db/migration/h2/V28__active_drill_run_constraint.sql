-- H2 使用生成列实现“每个用户最多一个 READY/ANSWERING run”。
DROP INDEX IF EXISTS uni_drill_run_active;
ALTER TABLE drill_run DROP COLUMN IF EXISTS active_marker;
ALTER TABLE drill_run ADD COLUMN active_marker BIGINT
    GENERATED ALWAYS AS (CASE WHEN status IN ('READY','ANSWERING') THEN user_id ELSE NULL END);
CREATE UNIQUE INDEX IF NOT EXISTS uni_drill_run_active ON drill_run (active_marker);
