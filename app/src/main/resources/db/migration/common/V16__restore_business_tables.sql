-- ============================================================
-- V16__restore_business_tables.sql
-- 补建 resume / interview / knowledge_base 等业务表。
-- 这些表由历史 V1 脚本创建（后删除），对方远端迁移 V2~V15 只覆盖
-- drill + app_user，未包含这些业务表。用新版本号 V16 补齐，避免与远端冲突。
-- 全部 IF NOT EXISTS，兼容已有环境。
-- ============================================================

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

-- ---------- 说明：pgvector 向量存储与触发器已移除 ----------
-- 原 vector_store 表（Spring AI pgvector 标准结构 + HNSW 索引）依赖 pgvector 扩展，
-- 嵌入式 H2 不支持且当前业务未使用（反重复走 trigram，见 NgramSimilarityGuard），已整体删除。
-- updated_at 触发器（plpgsql）同样移除：resume / interview_session 已在实体侧用 @PreUpdate 维护，
-- 其余无实体的业务表（knowledge_base / interview_schedule）updated_at 由默认值维护即可。
