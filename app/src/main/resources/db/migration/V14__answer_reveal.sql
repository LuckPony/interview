-- ============ 答案揭示边界：评分只基于「得到答案之前」的回答 ============
--
-- 背景：对话式辅导（LEARN chat）默认采用苏格拉底式引导，不直接给答案；
-- 但学生明确索要答案/提示时（点「看答案」按钮，或输入「告诉我答案」「我不会」等），
-- AI 会给出答案。该轮之后学生的回答可能只是照着答案复述，不能再计入评分，
-- 否则「结束并评分」的量化分数反映的是抄写能力而非独立思考能力。
--
-- answer_revealed_round 记录首次揭示答案/提示所在的轮次（drill_turn.round）：
--   null    = 从未索要答案 → finish 拼接全部用户回答判分
--   非空     = 第 N 轮起已揭示 → finish 只拼接 round < N 的用户回答
-- 只在 run 处于 READY/ANSWERING（评分前）时写入；已 GRADED 后的继续追问不改变它。
ALTER TABLE drill_run ADD COLUMN IF NOT EXISTS answer_revealed_round INT;

COMMENT ON COLUMN drill_run.answer_revealed_round IS
    '首次明确索要答案/提示的轮次（drill_turn.round）；null=从未索要，finish 评分只拼接该轮之前的用户回答';
