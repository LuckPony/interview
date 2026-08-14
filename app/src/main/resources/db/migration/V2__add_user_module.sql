
-----用户模块------
CREATE TABLE IF NOT EXISTS users(
    id               BIGSERIAL PRIMARY KEY ,
    username         VARCHAR(50)   NOT NULL UNIQUE ,
    password_hash    VARCHAR(255)  NOT NULL ,
    nickname         VARCHAR(50),
    role             VARCHAR(20)   NOT NULL DEFAULT 'USER' ,
    gender           VARCHAR(10),
    email            VARCHAR(100),
    phone            VARCHAR(20),
    birthday         DATE,
    avatar_url       VARCHAR(255),
    status           SMALLINT       NOT NULL DEFAULT 1,     ---1表示正常，0表示禁用
    extra_info       JSONB,
    last_login_at    TIMESTAMP  ,
    created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TRIGGER trg_users_updated_at
    BEFORE UPDATE ON users FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();