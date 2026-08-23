-- V5__corpus.sql
-- 个人资料 Corpus（让用户基于自己的书 / 项目资料学习）。
-- v1 只做文本层解析：Apache Tika（底层 PDFBox）抽字，支持 PDF / txt / md / docx。
-- 图片 / 扫描件（需 OCR / 视觉）本期不做；抽不到字时显式报错。

-- ============ 个人资料：corpus ============
CREATE TABLE IF NOT EXISTS corpus (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    name        VARCHAR(255) NOT NULL,          -- 原文件名
    source_type VARCHAR(20)  NOT NULL DEFAULT 'UPLOAD',
    text        TEXT         NOT NULL,           -- 解析出的纯文本
    char_count  INT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_corpus_user ON corpus (user_id);

-- ============ 一个学习方向可绑定一份资料 ============
ALTER TABLE study_plan ADD COLUMN IF NOT EXISTS corpus_id BIGINT REFERENCES corpus(id);
CREATE INDEX IF NOT EXISTS idx_study_plan_corpus ON study_plan (corpus_id);
