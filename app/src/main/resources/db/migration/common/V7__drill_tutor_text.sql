-- ============ 教学讲解：每轮判分后由 LLM 写一段老师式讲解 ============
-- 存在即代表讲解已落地；首次落库前为 null，前端按 null 不渲染处理。
-- 单轮字符串 200-400 字，写一次不更新（用户重新追问/重答会由新 turn 覆盖）。
ALTER TABLE drill_turn ADD COLUMN IF NOT EXISTS tutor_text TEXT;

COMMENT ON COLUMN drill_turn.tutor_text IS
    '教学讲解：判分落地后由 TutorGenerator 写一段老师式讲解（基于 stem + 评分点 + 判分 + 学生答案），帮用户理解这道题，不是判分报告';