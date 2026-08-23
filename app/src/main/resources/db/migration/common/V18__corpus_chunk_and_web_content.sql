-- ============ 资料结构化：拆块 + 概念映射 + 互联网内容预取 ============
--
-- 背景：资料（corpus）此前只存整篇文本，出题/对话时全文截断注入（MAX_INJECT_CHARS=20k），
-- 长资料会被硬截断、且对话/判分链路根本拿不到资料。本期改造：
--   1) corpus_chunk   —— 资料按逻辑主题切块（服务端启发式切边界，LLM 只标注标题/知识点/摘要，
--                        原文零改动、零丢失，不存在"切块截断信息"的问题）；
--   2) concept_chunk  —— 知识点（concept）↔ 资料块（chunk）映射：出题/对话/判分/复盘时
--                        按当前概念注入其命中块（检索而非截断）；
--   3) web_content    —— 建计划时默认预取每个知识点的互联网标准内容（web_search 一次），
--                        作为「资料之外的补充素材」随上下文注入，破除信息茧房。
-- 三张表都是新表，FK 用 ON DELETE CASCADE（与既有表手动级联不同：新表自管级联）。

-- ============ 资料块：corpus_chunk ============
CREATE TABLE IF NOT EXISTS corpus_chunk (
    id         BIGSERIAL PRIMARY KEY,
    corpus_id  BIGINT       NOT NULL REFERENCES corpus(id) ON DELETE CASCADE,
    seq        INT          NOT NULL,                  -- 块在资料中的顺序
    title      TEXT         NOT NULL,                  -- 块标题（如「第三章 内存模型」）
    topic      TEXT,                                   -- 该块对应的知识点名（LLM 标注，用于候选知识点 & 概念匹配）
    summary    TEXT,                                   -- 块摘要（LLM 生成，50-150 字）
    text       TEXT         NOT NULL,                  -- 原文块（服务端按边界切，内容零改动）
    char_count INT          NOT NULL DEFAULT 0,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uni_corpus_chunk_seq UNIQUE (corpus_id, seq)
);
CREATE INDEX IF NOT EXISTS idx_corpus_chunk_corpus ON corpus_chunk (corpus_id);

-- ============ 概念 ↔ 资料块 映射：concept_chunk ============
CREATE TABLE IF NOT EXISTS concept_chunk (
    concept_id BIGINT NOT NULL REFERENCES concept(id) ON DELETE CASCADE,
    chunk_id   BIGINT NOT NULL REFERENCES corpus_chunk(id) ON DELETE CASCADE,
    PRIMARY KEY (concept_id, chunk_id)
);

-- ============ 知识点互联网内容：web_content ============
-- 每个知识点最多一条（预取一次）；url 可空（搜索接口不保证回带引用链接）。
CREATE TABLE IF NOT EXISTS web_content (
    id         BIGSERIAL PRIMARY KEY,
    concept_id BIGINT       NOT NULL REFERENCES concept(id) ON DELETE CASCADE,
    url        TEXT,
    title      TEXT,
    text       TEXT         NOT NULL,
    char_count INT          NOT NULL DEFAULT 0,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uni_web_content_concept UNIQUE (concept_id)
);
CREATE INDEX IF NOT EXISTS idx_web_content_concept ON web_content (concept_id);
