-- 需求1：复习也基于子知识点。
-- 每日复习任务（REVIEW）生成时记录聚焦的子知识点，出题/开 run 时限定到该子点，
-- 与「先教后考」的 focusSubPoint 语义一致（null = 概念级复习）。
ALTER TABLE daily_task ADD COLUMN sub_point VARCHAR(200);
