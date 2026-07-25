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