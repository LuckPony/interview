-- PostgreSQL 原生部分唯一索引实现“每个用户最多一个 READY/ANSWERING run”。
DROP INDEX IF EXISTS uni_drill_run_active;
ALTER TABLE drill_run DROP COLUMN IF EXISTS active_marker;
CREATE UNIQUE INDEX uni_drill_run_active
    ON drill_run (user_id)
    WHERE status IN ('READY', 'ANSWERING');
