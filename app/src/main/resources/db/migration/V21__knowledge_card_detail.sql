-- 知识卡片：新增 detail 列，保存 AI 当时回复的完整内容（Markdown 原文），
-- 用于「查看答案（摘要）→ 查看详细答案（完整记录）」的两级查看机制。
ALTER TABLE knowledge_card ADD COLUMN IF NOT EXISTS detail TEXT;
