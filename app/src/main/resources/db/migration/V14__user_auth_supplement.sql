-- 用户认证补充：
-- 1) users 表补 verified 列（AppUser 实体需要，V2 建表时缺失）
-- 2) 邮箱验证码表（EmailVerifyCode 实体映射）
ALTER TABLE users ADD COLUMN IF NOT EXISTS verified BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE IF NOT EXISTS email_verify_code (
    id          BIGSERIAL PRIMARY KEY,
    email       VARCHAR(128) NOT NULL,
    code        VARCHAR(8)   NOT NULL,
    expires_at  TIMESTAMP    NOT NULL,
    used        BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
