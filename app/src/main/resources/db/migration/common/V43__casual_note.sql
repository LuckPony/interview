-- Casual Note (随手记) table: user-entered markdown notes attached to a concept and chat run.
CREATE TABLE IF NOT EXISTS casual_note (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    title VARCHAR(300) NOT NULL,
    content TEXT NOT NULL,
    concept_id BIGINT,
    concept_name VARCHAR(300),
    chat_id BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_casual_note_user ON casual_note(user_id);
CREATE INDEX IF NOT EXISTS idx_casual_note_concept ON casual_note(concept_id);
