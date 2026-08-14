-- V2__drill_schema.sql
-- 知识练习(drill)学习模块：五张核心表 + 物理闸门 + 种子概念
-- 设计原则：教学决策权在服务端，LLM 只做教具（出题/评分/追问）

-- ============ 1. 知识矩阵的一格：concept ============
CREATE TABLE IF NOT EXISTS concept (
    id          BIGSERIAL PRIMARY KEY,
    topic       VARCHAR(100) NOT NULL,          -- 知识主题（行），如 "Java 并发"
    layer       SMALLINT      NOT NULL,          -- 认知层（列）1-概念 2-机制 3-实现 4-权衡 5-故障
    name        VARCHAR(200) NOT NULL,           -- 概念显示名
    description TEXT,
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_layer CHECK (layer BETWEEN 1 AND 5)
);

-- ============ 2. 预生成题库：question_bank ============
-- 四维正交签名：(concept_ids[], probe_type, answer_mode, response_format)
-- arity = array_length(concept_ids,1)，是字段不是类别
CREATE TABLE IF NOT EXISTS question_bank (
    id              BIGSERIAL PRIMARY KEY,
    concept_ids     INTEGER[]     NOT NULL,       -- 参与的概念（单点=1，组合=2-3）
    probe_type      VARCHAR(20)   NOT NULL,       -- RECALL/CLOZE/REVERSE/TRAP/SCENARIO/CONTRAST/INTEGRATION
    answer_mode     VARCHAR(8)    NOT NULL DEFAULT 'WRITE', -- WRITE（SPEAK 预留）
    response_format VARCHAR(16)   NOT NULL,       -- FREE_TEXT/CHOICE/STRUCTURED/CODE
    arity           SMALLINT      NOT NULL DEFAULT 1,
    stem            TEXT          NOT NULL,       -- 题干
    points          JSONB,                        -- 评分点数组 [{text, weight}]
    mcq_options     JSONB,                        -- CHOICE 用：[{key, text, correct}]
    code_ref        VARCHAR(100),                 -- CODE 用：力扣题号等
    used_count      INTEGER       NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_arity CHECK (arity BETWEEN 1 AND 3)
);

-- ============ 3. 一次作答实例：drill_run（状态机 + 物理闸门） ============
CREATE TABLE IF NOT EXISTS drill_run (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT        NOT NULL,
    question_id     BIGINT        NOT NULL REFERENCES question_bank(id),
    mode            VARCHAR(12)   NOT NULL DEFAULT 'LEARN',  -- LEARN/REHEARSAL
    answer_mode     VARCHAR(8)    NOT NULL DEFAULT 'WRITE',
    status          VARCHAR(16)   NOT NULL DEFAULT 'READY',  -- READY/ANSWERING/SUBMITTED/GRADED/PARKED
    timing          VARCHAR(12),                   -- NONE/COUNTDOWN（opt-in 计时）
    open_book       BOOLEAN       NOT NULL DEFAULT FALSE,
    active_seconds  INTEGER,                       -- 有效作答时长（心跳累计）
    transcript      TEXT,                         -- SPEAK 预留
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 物理闸门：同一用户同时只允许一个未闭环的作答（READY/ANSWERING）
-- 并发插入第二个会触发唯一约束冲突 -> 服务端返回 409
CREATE UNIQUE INDEX IF NOT EXISTS uni_drill_run_active
    ON drill_run (user_id) WHERE status IN ('READY', 'ANSWERING');

-- ============ 4. 判分结果：grade_result ============
-- by_concept 统一格式：[{conceptId, role, pointResults[], extraCorrect[], factualErrors[]}]
CREATE TABLE IF NOT EXISTS grade_result (
    id              BIGSERIAL PRIMARY KEY,
    run_id          BIGINT        NOT NULL REFERENCES drill_run(id),
    question_id     BIGINT        NOT NULL,
    answer_hash     VARCHAR(64),                  -- 答案指纹，去重用
    by_concept      JSONB         NOT NULL,       -- 逐概念逐点判分结果
    raw_score       NUMERIC(5,2),                 -- 服务端计算的总分 0-100
    grade           VARCHAR(8),                    -- FSRS 档：AGAIN/HARD/GOOD/EASY
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_grade_result_q_hash ON grade_result (question_id, answer_hash);

-- ============ 5. 掌握度：mastery ============
-- 深度画像由 GROUP BY topic, MAX(layer) 直出，不建 Elo
CREATE TABLE IF NOT EXISTS mastery (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT        NOT NULL,
    concept_id      BIGINT        NOT NULL REFERENCES concept(id),
    mastery_level   SMALLINT      NOT NULL DEFAULT 0,   -- 0-3（3=模拟面试达标）
    last_grade      VARCHAR(8),
    due_at          TIMESTAMP,                       -- 下次复习时间（FSRS 排程）
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uni_mastery_user_concept UNIQUE (user_id, concept_id)
);

-- ============ updated_at 触发器（沿用 V1 的 update_updated_at_column） ============
CREATE TRIGGER trg_concept_updated_at
    BEFORE UPDATE ON concept FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER trg_question_bank_updated_at
    BEFORE UPDATE ON question_bank FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER trg_drill_run_updated_at
    BEFORE UPDATE ON drill_run FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER trg_mastery_updated_at
    BEFORE UPDATE ON mastery FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- ============ 种子概念（让 demo 可端到端跑通） ============
-- 一个主题 × 五层，足以演示"信息茧房破除（逼出 L3-L5）"与"深度画像"
INSERT INTO concept (topic, layer, name, description) VALUES
('Java 并发', 1, '线程与 Runnable',   '线程的基本创建方式、生命周期、Runnable 与 Callable 区别'),
('Java 并发', 2, 'synchronized 机制', '内置锁的加锁释放语义、可重入性、锁对象选择'),
('Java 并发', 3, 'AQS 实现原理',      'CLH 队列、state 状态、独占/共享模式获取释放流程'),
('Java 并发', 4, '锁的选型权衡',      'synchronized vs ReentrantLock vs 读写锁的适用场景与开销'),
('Java 并发', 5, '死锁排查与恢复',    '死锁四个必要条件、jstack 定位、避免与检测策略'),
('JVM',      1, '内存区域划分',        '堆/栈/方法区/直接内存的职责与线程私有性'),
('JVM',      3, 'GC 垃圾回收',         '可达性分析、分代收集、G1 回收器运作过程'),
('数据库',    2, '索引底层结构',        'B+ 树索引、聚簇/非聚簇、覆盖索引与回表');
