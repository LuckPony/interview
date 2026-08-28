-- 子知识点讲解页的答疑对话（仅当前用户私有，不判分、不进 run、不动 mastery、不反哺讲解正文）。
-- role: 'user' = 学生提问，'assistant' = AI 回答。anchor 为学生选中的讲解片段（可空）。
-- 删除由用户在前端多选 + 二次确认后按 id 删除（仅能删自己的记录）。
CREATE TABLE IF NOT EXISTS lesson_qa_message (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT       NOT NULL,
    concept_id BIGINT       NOT NULL,
    sub_point  VARCHAR(300) NOT NULL,
    role       VARCHAR(10)  NOT NULL,
    text       TEXT         NOT NULL,
    anchor     TEXT,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_lesson_qa_user_sub ON lesson_qa_message(user_id, concept_id, sub_point);
