-- ============ 先教后考：知识点拆解 + 子知识点讲解缓存 ============
--
-- 背景：练习时用户可能完全不理解某个知识点，需要 AI 先「教」再「考」。
-- 但一个 concept（如「Python 基础语法」）往往包含多个子知识点，一整段讲解会讲不全。
-- 本期落地「拆解版」：
--   1) concept.lesson_outline —— 缓存该概念拆解出的子知识点清单（JSON 数组字符串，
--        同一概念不重复拆解）；
--   2) concept_lesson           —— 按 (concept_id, sub_point) 缓存每个子知识点的讲解文本，
--        同一子点重复练不重复调 LLM，不同子点各有各的讲解。

ALTER TABLE concept ADD COLUMN IF NOT EXISTS lesson_outline TEXT;

CREATE TABLE IF NOT EXISTS concept_lesson (
    id          BIGSERIAL PRIMARY KEY,
    concept_id  BIGINT        NOT NULL REFERENCES concept(id) ON DELETE CASCADE,
    sub_point   VARCHAR(300)  NOT NULL,                  -- 子知识点名（拆解清单里的一项）
    lesson_text TEXT          NOT NULL,                  -- 该子知识点的讲解（Markdown）
    char_count  INT           NOT NULL DEFAULT 0,
    created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uni_concept_lesson_subpoint UNIQUE (concept_id, sub_point)
);
CREATE INDEX IF NOT EXISTS idx_concept_lesson_concept ON concept_lesson (concept_id);
