-- AI 运行设置：用户在「设置页」配置的模型 / api-key / base-url。
-- 单行（id=1），settings_json 存 JSON：{ provider, baseUrl, apiKey, model, temperature }。
CREATE TABLE IF NOT EXISTS ai_setting (
    id            INT PRIMARY KEY DEFAULT 1,
    settings_json TEXT NOT NULL,
    updated_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
