-- 放宽 drill_turn.round 的上限。
--
-- 背景：V3 里 `chk_round CHECK (round BETWEEN 0 AND 2)` 是给旧 REHEARSAL「最多 2 轮追问」设计的，
-- 但 LEARN 聊天按条消息递增 round（不限轮数），追问场 maxRound 也到 10。
-- 当用户聊到第 4 条消息（round=3）时，插入 drill_turn 会违反 chk_round → 500。
--
-- round 只是「轮次排序序号」，不需要上限，这里放宽为仅要求非负。
ALTER TABLE drill_turn DROP CONSTRAINT IF EXISTS chk_round;
ALTER TABLE drill_turn ADD CONSTRAINT chk_round CHECK (round >= 0);
