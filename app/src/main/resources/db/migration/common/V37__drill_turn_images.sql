-- 对话消息附带图片：用户可在聊天中上传/粘贴截图，图片以 data URL 存于 drill_turn。
-- 仅当当前模型支持视觉时允许上传（服务端校验）；历史对话回看时一并展示。
ALTER TABLE drill_turn ADD COLUMN IF NOT EXISTS image_json TEXT;
