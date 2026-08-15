-- ============================================================
-- V16__restore_business_tables.sql
-- 补建 resume / interview / knowledge_base 等业务表。
-- 这些表由历史 V1 脚本创建（后删除），对方远端迁移 V2~V15 只覆盖
-- drill + app_user，未包含这些业务表。用新版本号 V16 补齐，避免与远端冲突。
-- 全部 IF NOT EXISTS，兼容已有环境。
-- ============================================================

-- pgvector 扩展（docker-compose 用 pgvector/pgvector:pg16 镜像，自带该扩展；幂等启用）
CREATE EXTENSION IF NOT EXISTS vector;

-- ---------- 简历模块 ----------
CREATE TABLE IF NOT EXISTS resume (
    id            BIGSERIAL PRIMARY KEY,
    original_name VARCHAR(255) NOT NULL,
    file_type     VARCHAR(50)  NOT NULL,
    file_size     BIGINT       NOT NULL DEFAULT 0,
    storage_key   VARCHAR(255) NOT NULL,
    content_hash  VARCHAR(64),
    resume_text   TEXT,
    status        VARCHAR(20)  NOT NULL DEFAULT 'UPLOADED',
    error_message TEXT,
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS resume_analysis (
    id            BIGSERIAL PRIMARY KEY,
    resume_id     BIGINT NOT NULL REFERENCES resume(id) ON DELETE CASCADE,
    raw_json      TEXT,
    overall_score INTEGER,
    summary       TEXT,
    strengths     TEXT,
    weaknesses    TEXT,
    suggestions   TEXT,
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ---------- 模拟面试会话 ----------
CREATE TABLE IF NOT EXISTS interview_session (
    id                   VARCHAR(36) PRIMARY KEY,
    resume_id            BIGINT REFERENCES resume(id),
    skill_id             VARCHAR(50),
    difficulty           VARCHAR(20),
    status               VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS',
    total_questions      INTEGER NOT NULL DEFAULT 0,
    current_question_index INTEGER NOT NULL DEFAULT 0,
    total_score          INTEGER,
    evaluation_json      TEXT,
    llm_provider         VARCHAR(50),
    created_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS interview_answer (
    id             BIGSERIAL PRIMARY KEY,
    session_id     VARCHAR(36) NOT NULL REFERENCES interview_session(id) ON DELETE CASCADE,
    question_index INTEGER NOT NULL,
    question_text  TEXT NOT NULL,
    answer_text    TEXT,
    score          INTEGER,
    feedback       TEXT,
    is_follow_up   BOOLEAN NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ---------- 面试安排 ----------
CREATE TABLE IF NOT EXISTS interview_schedule (
    id             BIGSERIAL PRIMARY KEY,
    company_name   VARCHAR(255) NOT NULL,
    job_title      VARCHAR(255),
    interview_time TIMESTAMP NOT NULL,
    contact_info   VARCHAR(255),
    meeting_link   VARCHAR(500),
    location       VARCHAR(255),
    notes          TEXT,
    status         VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    source         VARCHAR(50),
    created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ---------- 知识库 ----------
CREATE TABLE IF NOT EXISTS knowledge_base (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    description TEXT,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS knowledge_base_document (
    id                BIGSERIAL PRIMARY KEY,
    knowledge_base_id BIGINT NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
    original_name     VARCHAR(255) NOT NULL,
    storage_key       VARCHAR(255) NOT NULL,
    vector_status     VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    chunk_count       INTEGER DEFAULT 0,
    created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ---------- 向量存储（Spring AI pgvector 标准结构） ----------
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

-- ---------- updated_at 触发器（幂等） ----------
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_resume_updated_at ON resume;
CREATE TRIGGER trg_resume_updated_at
    BEFORE UPDATE ON resume FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

DROP TRIGGER IF EXISTS trg_interview_session_updated_at ON interview_session;
CREATE TRIGGER trg_interview_session_updated_at
    BEFORE UPDATE ON interview_session FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

DROP TRIGGER IF EXISTS trg_knowledge_base_updated_at ON knowledge_base;
CREATE TRIGGER trg_knowledge_base_updated_at
    BEFORE UPDATE ON knowledge_base FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

DROP TRIGGER IF EXISTS trg_interview_schedule_updated_at ON interview_schedule;
CREATE TRIGGER trg_interview_schedule_updated_at
    BEFORE UPDATE ON interview_schedule FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
