-- 兼容曾执行过旧 V21（子知识点迁移）的历史数据库。
-- 远程 V21__knowledge_card_detail.sql 会因版本 21 已存在而被 Flyway 跳过，
-- 因此使用新的版本再次幂等补齐 knowledge_card.detail。
ALTER TABLE knowledge_card ADD COLUMN IF NOT EXISTS detail TEXT;
