-- 知识卡片：日常对话沉淀的统一单元
CREATE TABLE IF NOT EXISTS knowledge_card (
  id                BIGSERIAL PRIMARY KEY,
  user_id           BIGINT       NOT NULL,
  source            VARCHAR(20)  NOT NULL DEFAULT 'CHAT',
  question          TEXT         NOT NULL,
  answer            TEXT,
  tags              TEXT,                       -- 逗号分隔
  concept_id        BIGINT,                     -- 可选关联概念，不强制
  plan_id           BIGINT,                     -- 可选关联学习方向
  due_at            TIMESTAMP,                  -- 下次复习时间
  review_count      INT          NOT NULL DEFAULT 0,
  last_reviewed_at  TIMESTAMP,
  created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_kc_user_due    ON knowledge_card (user_id, due_at);
CREATE INDEX IF NOT EXISTS idx_kc_user_source ON knowledge_card (user_id, source);