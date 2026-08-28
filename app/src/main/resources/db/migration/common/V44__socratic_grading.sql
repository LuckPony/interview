-- 苏格拉底教学评分体系：移除补救测试/三阶段字段，加两级评分字段
-- 删：phase/first_grade/transfer_*（旧三阶段+补救测试）
-- 保留：current_round/max_round/answer_revealed_round（模拟面试 REHEARSAL 仍在用）
ALTER TABLE drill_run
    DROP COLUMN IF EXISTS phase,
    DROP COLUMN IF EXISTS first_grade,
    DROP COLUMN IF EXISTS transfer_count,
    DROP COLUMN IF EXISTS transfer_max,
    DROP COLUMN IF EXISTS transfer_stem,
    DROP COLUMN IF EXISTS transfer_points,
    DROP COLUMN IF EXISTS transfer_concept_ids;

-- DrillRun 苏格拉底评分字段
ALTER TABLE drill_run
    ADD COLUMN socratic_state VARCHAR(20) NOT NULL DEFAULT 'ANSWERING',
    ADD COLUMN pre_grade VARCHAR(10),
    ADD COLUMN final_grade VARCHAR(10),
    ADD COLUMN guided BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN guide_rounds INT NOT NULL DEFAULT 0,
    ADD COLUMN revealed BOOLEAN NOT NULL DEFAULT FALSE;

-- DrillTurn 加每轮苏格拉底判定
ALTER TABLE drill_turn
    ADD COLUMN judge_state VARCHAR(20),
    ADD COLUMN coverage DECIMAL(4,3),
    ADD COLUMN fatal_gap BOOLEAN;

-- GradeResult 加两级评分记录
ALTER TABLE grade_result
    ADD COLUMN pre_grade VARCHAR(10),
    ADD COLUMN final_grade VARCHAR(10),
    ADD COLUMN guided BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN guide_rounds INT NOT NULL DEFAULT 0,
    ADD COLUMN revealed BOOLEAN NOT NULL DEFAULT FALSE;
