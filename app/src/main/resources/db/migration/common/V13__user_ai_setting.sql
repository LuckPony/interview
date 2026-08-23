-- 用户个人 AI 配置：每人一行（user_id 主键），settings_json 存 { provider, baseUrl, apiKey, model, temperature }。
-- 取值优先级：桌面端 X-LLM-Key 请求头（只用不存）> 本表（Web 端按用户保存）> 启动配置（.env，本地模式）。
-- 老 ai_setting 全局表不再参与取值（防止「全站共享 key」复活），表本身保留不删。
CREATE TABLE IF NOT EXISTS user_ai_setting (
    user_id       BIGINT PRIMARY KEY,
    settings_json TEXT NOT NULL,
    updated_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
