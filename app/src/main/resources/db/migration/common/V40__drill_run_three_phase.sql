-- 三阶段练习（独立作答 → 教学讲解 → 迁移测试）：
--   phase              当前阶段：FIRST_ANSWER(待独立作答) / TUTORING(讲解中) / TRANSFER_TEST(迁移测试中) / DONE(已结束)
--   first_grade        阶段1锁定的基础档位（AGAIN/HARD/GOOD/EASY），null=尚未判分
--   transfer_count     已完成的迁移测试轮数（防无限追问）
--   transfer_max       迁移测试轮数上限（默认 2）
--   transfer_stem      迁移测试题题干（结合已掌握知识点生成，不落 question_bank）
--   transfer_points    迁移测试题评分点（GeneratedQuestion JSON）
--   transfer_concept_ids 迁移测试题概念 id 顺序（index 0 = PRIMARY 当前概念）
ALTER TABLE drill_run
    ADD COLUMN phase VARCHAR(20) NOT NULL DEFAULT 'FIRST_ANSWER',
    ADD COLUMN first_grade VARCHAR(10),
    ADD COLUMN transfer_count INT NOT NULL DEFAULT 0,
    ADD COLUMN transfer_max INT NOT NULL DEFAULT 2,
    ADD COLUMN transfer_stem TEXT,
    ADD COLUMN transfer_points JSONB,
    ADD COLUMN transfer_concept_ids JSONB;
