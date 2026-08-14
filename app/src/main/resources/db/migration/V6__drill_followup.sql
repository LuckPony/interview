-- ============ 追问来源：指向被追问的 LEARN run ============
-- drill_run.mode=REHEARSAL 且 source_run_id 非空 = 由 LEARN grade 触发的"追问"场，
-- 语义上是问深一点弄懂同一道题，不算正式面试。
-- settle 时通过 source_run_id 判定跳过 mastery 应用（"L3 不取"）。
-- 不加索引：追问场每用户零到几条，单次按 run.id 查走主键。

ALTER TABLE drill_run ADD COLUMN IF NOT EXISTS source_run_id BIGINT REFERENCES drill_run(id);

COMMENT ON COLUMN drill_run.source_run_id IS
    '追问来源 run_id；非空表示该 REHEARSAL run 是从已有 LEARN run 的 grade 卡的"继续追问"按钮 spawn 而来';
