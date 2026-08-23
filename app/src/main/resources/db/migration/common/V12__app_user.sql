-- 注册登录：应用用户账号。
-- email 唯一，密码存 BCrypt 哈希；verified 标记邮箱是否通过验证。
CREATE TABLE IF NOT EXISTS app_user (
    id            BIGSERIAL PRIMARY KEY,
    email         VARCHAR(128) NOT NULL UNIQUE,
    password_hash VARCHAR(128) NOT NULL,
    display_name  VARCHAR(64),
    verified      BOOLEAN NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 邮箱验证码：注册时生成，6 位数字，15 分钟有效，用过即失效。
CREATE TABLE IF NOT EXISTS email_verify_code (
    id         BIGSERIAL PRIMARY KEY,
    email      VARCHAR(128) NOT NULL,
    code       VARCHAR(8)   NOT NULL,
    expires_at TIMESTAMP    NOT NULL,
    used       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_email_verify_code_email ON email_verify_code (email);
