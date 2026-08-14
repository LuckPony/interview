-- V3__drill_rehearsal_and_note.sql
-- 痛点 4（面试说不出）：REHEARSAL 多轮追问
-- 痛点 7（笔记没过脑子）：内化笔记，schema 物理上写不出标准答案

-- ============ 6. REHEARSAL 多轮对话：drill_turn ============
-- 一个 REHEARSAL run 有 1 道主问 + 最多 2 轮追问 = 最多 3 个 QUESTION turn
-- round 从 0 开始（0=主问，1/2=追问）
CREATE TABLE IF NOT EXISTS drill_turn (
    id          BIGSERIAL PRIMARY KEY,
    run_id      BIGINT      NOT NULL REFERENCES drill_run(id),
    round       SMALLINT    NOT NULL,
    stem        TEXT        NOT NULL,          -- 本轮问题（主问=题库题干，追问=LLM 生成）
    points      JSONB,                          -- 本轮评分点
    raw_answer  TEXT,                           -- 用户本轮作答
    by_concept  JSONB,                          -- 本轮逐点判分
    raw_score   NUMERIC(5,2),
    passed      BOOLEAN,                        -- 本轮 content 是否通过
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_round CHECK (round BETWEEN 0 AND 2),
    CONSTRAINT uni_drill_turn_run_round UNIQUE (run_id, round)
);
CREATE INDEX IF NOT EXISTS idx_drill_turn_run ON drill_turn (run_id);

-- ============ 7. 内化笔记：drill_note ============
-- 痛点 7 的物理解法：这张表【故意】没有 summary / correct_answer / ai_explanation 列
-- 用户只能写"自己的话 / 发现的缺口 / 下一步动作"，抄 AI 答案在结构上就不成立
CREATE TABLE IF NOT EXISTS drill_note (
    id           BIGSERIAL PRIMARY KEY,
    run_id       BIGINT      NOT NULL REFERENCES drill_run(id),
    user_id      BIGINT      NOT NULL,
    my_words     TEXT        NOT NULL,          -- 用自己的话复述（服务端校验非抄写）
    gap_found    TEXT,                           -- 这次暴露出的缺口
    next_action  TEXT,                           -- 下一步打算怎么补
    overlap_ratio NUMERIC(4,3),                 -- 与题干/评分点的 trigram 重合度（审计留痕）
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uni_drill_note_run UNIQUE (run_id)
);
CREATE INDEX IF NOT EXISTS idx_drill_note_user ON drill_note (user_id);

-- ============ REHEARSAL 轮次游标（挂在 drill_run 上，避免再建表） ============
ALTER TABLE drill_run ADD COLUMN IF NOT EXISTS current_round SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE drill_run ADD COLUMN IF NOT EXISTS max_round     SMALLINT NOT NULL DEFAULT 0;

-- ============ 组合题需要更多种子概念（同 layer 跨 topic 才凑得出 L4+ 组合） ============
INSERT INTO concept (topic, layer, name, description) VALUES
('JVM',      2, '类加载机制',      '加载/验证/准备/解析/初始化五阶段与双亲委派'),
('JVM',      4, 'GC 器选型权衡',   'CMS/G1/ZGC 的停顿与吞吐取舍、堆大小与 SLA 的关系'),
('JVM',      5, 'OOM 排查',        '堆外/元空间/堆内溢出的区分、dump 分析与定位路径'),
('数据库',    1, '事务与 ACID',     '原子性一致性隔离性持久性的含义与实现手段'),
('数据库',    3, 'MVCC 实现',       'undo log 版本链、ReadView 可见性判断规则'),
('数据库',    4, '隔离级别权衡',    'RC/RR 的幻读表现、加锁开销与业务一致性诉求的取舍'),
('数据库',    5, '慢查询定位',      'explain 执行计划、索引失效场景、锁等待与死锁日志'),
('Java 并发', 3, '线程池执行流程',  'corePoolSize/队列/maximumPoolSize 的入队与拒绝顺序')
ON CONFLICT DO NOTHING;
