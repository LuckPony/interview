-- V1__init_schema.sql
-- 首次建库：创建平台所有基础表
-- Flyway 会自动记录执行状态到 flyway_schema_history 表
CREATE EXTENSION IF NOT EXISTS vector;

-------简历模块-------
CREATE TABLE IF NOT EXISTS resume(
    id                  BIGSERIAL PRIMARY KEY,
    original_name       VARCHAR(255) NOT NULL,
    file_type           VARCHAR(50) NOT NULL,
    file_size           BIGINT      NOT NULL DEFAULT 0,
    storage_key         VARCHAR(255) NOT NULL ,       -- MinIO 中的存储路径
    content_hash        varchar(64),                  --SHA-256 内容哈希，用于去重
    resume_text         TEXT,                         --Tika解析出的文本
    status              VARCHAR(20) NOT NULL  DEFAULT 'UPLOADED',
    error_message       TEXT,
    created_at          TIMESTAMP NOT NULL  DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP NOT NULL  DEFAULT CURRENT_TIMESTAMP
);

------简历分析结果--------
CREATE TABLE IF NOT EXISTS resume_analysis (
    id              BIGSERIAL PRIMARY KEY,
    resume_id       BIGINT NOT NULL REFERENCES resume(id) ON DELETE CASCADE,
    raw_json        TEXT,                         -- AI 返回的原始 JSON
    overall_score   INTEGER,
    summary         TEXT,
    strengths       TEXT,          -- 逗号分隔的优势列表
    weaknesses      TEXT,          -- 逗号分隔的不足列表
    suggestions     TEXT,          -- 逗号分隔的建议列表
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

--------模拟面试会话-------
CREATE TABLE IF NOT EXISTS interview_session (
    id              VARCHAR(36) PRIMARY KEY,       -- UUID
    resume_id       BIGINT REFERENCES resume(id),
    skill_id        VARCHAR(50),                   -- 面试方向（如 java-backend）
    difficulty      VARCHAR(20),                   -- JUNIOR / MIDDLE / SENIOR
    status          VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS', -- IN_PROGRESS / COMPLETED / TERMINATED
    total_questions INTEGER NOT NULL DEFAULT 0,
    current_question_index INTEGER NOT NULL DEFAULT 0,
    total_score     INTEGER,
    evaluation_json TEXT,                          -- 完整评估结果 JSON
    llm_provider    VARCHAR(50),
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-----面试回答记录-------
CREATE TABLE IF NOT EXISTS interview_answer (
    id              BIGSERIAL PRIMARY KEY,
    session_id      VARCHAR(36) NOT NULL REFERENCES interview_session(id) ON DELETE CASCADE,
    question_index  INTEGER NOT NULL,
    question_text   TEXT NOT NULL,
    answer_text     TEXT,
    score           INTEGER,                      -- AI 评分 0-100
    feedback        TEXT,                          -- AI 反馈
    is_follow_up    BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

------知识库-------
CREATE TABLE IF NOT EXISTS knowledge_base (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(100) NOT NULL,
    description     TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-------知识库文档------
CREATE TABLE IF NOT EXISTS knowledge_base_document (
    id              BIGSERIAL PRIMARY KEY,
    knowledge_base_id BIGINT NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
    original_name   VARCHAR(255) NOT NULL,
    storage_key     VARCHAR(255) NOT NULL,   -- ChromaDB 中的存储路径
    vector_status   VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING / VECTORIZED / FAILED
    chunk_count     INTEGER DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-----向量存储（Spring AI pgvector 标准结构--------
CREATE TABLE IF NOT EXISTS vector_store (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    content         TEXT,
    metadata        JSONB DEFAULT '{}'::jsonb,
    embedding       VECTOR(1024)
);

-- 创建 HNSW 索引加速向量检索
CREATE INDEX IF NOT EXISTS idx_vector_store_embedding
    ON vector_store
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-----面试安排------
CREATE TABLE IF NOT EXISTS interview_schedule (
    id              BIGSERIAL PRIMARY KEY,
    company_name    VARCHAR(255) NOT NULL,
    job_title       VARCHAR(255),
    interview_time  TIMESTAMP NOT NULL,
    contact_info    VARCHAR(255),
    meeting_link    VARCHAR(500),
    location        VARCHAR(255),
    notes           TEXT,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING / COMPLETED / CANCELLED / EXPIRED
    source          VARCHAR(50),                   -- MANUAL / PARSED
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

------ 更新时间触发器---------
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- 为需要自动更新 updated_at 的表创建触发器
CREATE TRIGGER trg_resume_updated_at
    BEFORE UPDATE ON resume FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER trg_interview_session_updated_at
    BEFORE UPDATE ON interview_session FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER trg_knowledge_base_updated_at
    BEFORE UPDATE ON knowledge_base FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER trg_interview_schedule_updated_at
    BEFORE UPDATE ON interview_schedule FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();