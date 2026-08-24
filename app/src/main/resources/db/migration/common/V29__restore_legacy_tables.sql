-- Restore PostgreSQL-only legacy tables present in historical full deployments.
-- vector_store requires the pgvector extension and therefore belongs in the PostgreSQL location.

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS users (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(50)  NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    nickname      VARCHAR(50),
    role          VARCHAR(20)  NOT NULL DEFAULT 'USER',
    gender        VARCHAR(10),
    email         VARCHAR(100),
    phone         VARCHAR(20),
    birthday      DATE,
    avatar_url    VARCHAR(255),
    status        SMALLINT     NOT NULL DEFAULT 1,
    extra_info    JSONB,
    last_login_at TIMESTAMP,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    verified      BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_users_updated_at ON users;
CREATE TRIGGER trg_users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TABLE IF NOT EXISTS interview_question (
    session_id    VARCHAR(36) PRIMARY KEY
                  REFERENCES interview_session(id) ON DELETE CASCADE,
    questions_json TEXT NOT NULL,
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS vector_store (
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    content   TEXT,
    metadata  JSONB DEFAULT '{}'::jsonb,
    embedding VECTOR(1024)
);

CREATE INDEX IF NOT EXISTS idx_vector_store_embedding
    ON vector_store
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
