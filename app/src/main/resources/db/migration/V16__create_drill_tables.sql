-- ============================================================
-- V16__create_drill_tables.sql
-- 面霸 drill（练习/学习）模块的 11 张表。
-- 之前从未写过迁移（靠早期 ddl-auto 建表），ddl-auto 切为 none 后
-- 全新环境无法建表，故补齐。字段与 JPA 实体完全对齐。
--
-- 说明：
--   - 枚举字段（mode / answer_mode / status / probe_type / response_format /
--     grade 等）在 JPA 里是 @Enumerated(EnumType.STRING)，映射 varchar。
--   - JSON 字段（points / by_concept / mcq_options）实体用 @JdbcTypeCode(JSON)
--     映射 jsonb。
--   - concept_ids 是 PostgreSQL 原生数组 integer[]。
--   - 全部 IF NOT EXISTS，兼容已有环境。
-- ============================================================

-- ---------- 知识矩阵格（topic × layer） ----------
CREATE TABLE IF NOT EXISTS concept (
    id           BIGSERIAL PRIMARY KEY,
    topic        VARCHAR(255) NOT NULL,
    layer        INTEGER NOT NULL,
    name         VARCHAR(255) NOT NULL,
    description  TEXT,
    study_plan_id BIGINT,
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ---------- 用户上传的个人资料（书籍/项目文档） ----------
CREATE TABLE IF NOT EXISTS corpus (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL,
    name        VARCHAR(255) NOT NULL,
    source_type VARCHAR(50) NOT NULL DEFAULT 'UPLOAD',
    text        TEXT NOT NULL,
    char_count  INTEGER NOT NULL DEFAULT 0,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ---------- 学习方向容器 ----------
CREATE TABLE IF NOT EXISTS study_plan (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT NOT NULL,
    title      VARCHAR(255) NOT NULL,
    goal       TEXT,
    status     VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    corpus_id  BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ---------- 预生成题库（四维正交签名） ----------
CREATE TABLE IF NOT EXISTS question_bank (
    id              BIGSERIAL PRIMARY KEY,
    concept_ids     INTEGER[] NOT NULL,
    probe_type      VARCHAR(30) NOT NULL,
    answer_mode     VARCHAR(20) NOT NULL DEFAULT 'WRITE',
    response_format VARCHAR(20) NOT NULL,
    arity           INTEGER NOT NULL DEFAULT 1,
    stem            TEXT NOT NULL,
    points          JSONB,
    mcq_options     JSONB,
    code_ref        VARCHAR(255),
    used_count      INTEGER NOT NULL DEFAULT 0
);

-- ---------- 每日学习任务 ----------
CREATE TABLE IF NOT EXISTS daily_task (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL,
    plan_id     BIGINT,
    task_date   DATE NOT NULL,
    kind        VARCHAR(20) NOT NULL,
    concept_id  BIGINT NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    question_id BIGINT,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ---------- 作答实例（状态机） ----------
CREATE TABLE IF NOT EXISTS drill_run (
    id               BIGSERIAL PRIMARY KEY,
    user_id          BIGINT NOT NULL,
    question_id      BIGINT NOT NULL,
    mode             VARCHAR(20) NOT NULL DEFAULT 'LEARN',
    answer_mode      VARCHAR(20) NOT NULL DEFAULT 'WRITE',
    status           VARCHAR(20) NOT NULL DEFAULT 'READY',
    timing           VARCHAR(20),
    open_book        BOOLEAN NOT NULL DEFAULT FALSE,
    active_seconds   INTEGER,
    transcript       TEXT,
    current_round    INTEGER NOT NULL DEFAULT 0,
    max_round        INTEGER NOT NULL DEFAULT 0,
    source_run_id    BIGINT,
    created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ---------- REHEARSAL 一轮问答 ----------
CREATE TABLE IF NOT EXISTS drill_turn (
    id          BIGSERIAL PRIMARY KEY,
    run_id      BIGINT NOT NULL,
    round       INTEGER NOT NULL,
    stem        TEXT NOT NULL,
    points      JSONB,
    raw_answer  TEXT,
    by_concept  JSONB,
    raw_score   NUMERIC(10, 4),
    passed      BOOLEAN,
    tutor_text  TEXT,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ---------- 判分结果 ----------
CREATE TABLE IF NOT EXISTS grade_result (
    id          BIGSERIAL PRIMARY KEY,
    run_id      BIGINT NOT NULL,
    question_id BIGINT NOT NULL,
    answer_hash VARCHAR(64),
    by_concept  JSONB NOT NULL,
    raw_score   NUMERIC(10, 4),
    grade       VARCHAR(10),
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ---------- 掌握度 ----------
CREATE TABLE IF NOT EXISTS mastery (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT NOT NULL,
    concept_id    BIGINT NOT NULL,
    mastery_level INTEGER NOT NULL DEFAULT 0,
    last_grade    VARCHAR(10),
    due_at        TIMESTAMP,
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ---------- 内化笔记 ----------
CREATE TABLE IF NOT EXISTS drill_note (
    id            BIGSERIAL PRIMARY KEY,
    run_id        BIGINT NOT NULL,
    user_id       BIGINT NOT NULL,
    my_words      TEXT NOT NULL,
    gap_found     TEXT,
    next_action   TEXT,
    overlap_ratio NUMERIC(6, 4),
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ---------- AI 复盘缓存（runId 主键，一份作答一份复盘） ----------
CREATE TABLE IF NOT EXISTS drill_review (
    run_id      BIGINT PRIMARY KEY,
    gap_summary TEXT,
    approach    TEXT,
    mnemonic    TEXT,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ---------- 常用查询索引（按实体查询习惯补齐） ----------
CREATE INDEX IF NOT EXISTS idx_drill_run_user_status ON drill_run (user_id, status);
CREATE INDEX IF NOT EXISTS idx_drill_run_user_question ON drill_run (user_id, question_id);
CREATE INDEX IF NOT EXISTS idx_drill_turn_run ON drill_turn (run_id);
CREATE INDEX IF NOT EXISTS idx_grade_result_run ON grade_result (run_id);
CREATE INDEX IF NOT EXISTS idx_mastery_user ON mastery (user_id);
CREATE INDEX IF NOT EXISTS idx_daily_task_user_date ON daily_task (user_id, task_date);
CREATE INDEX IF NOT EXISTS idx_concept_plan ON concept (study_plan_id);
