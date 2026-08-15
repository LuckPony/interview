-- ============ 清空数据库里保存的 API Key：不再支持服务器级 / 共享 key ============
--
-- 背景：API Key 只允许来自「设置页用户自己保存」或「桌面端请求头 X-LLM-Key（只用不存）」，
-- 不允许任何服务器级 / 共享 / 默认 key 存在（application.yml 的 providers 已不再配置 key）。
-- 本迁移把历史遗留的 key 全部清掉：
--   1) 老全局 ai_setting 表（曾用于全站共享 key，已不参与取值）整表清空；
--   2) user_ai_setting 里每行的 settings_json 抹掉 apiKey 字段（保留 provider/baseUrl/model/temperature），
--      之后用户使用任何 AI 功能前，都必须重新在「设置」页填写自己的 key。


UPDATE user_ai_setting
SET settings_json = jsonb_set(settings_json::jsonb, '{apiKey}', '""'::jsonb, true)::text,
    updated_at = now()
WHERE settings_json::jsonb ->> 'apiKey' IS NOT NULL
  AND settings_json::jsonb ->> 'apiKey' <> '';
