-- ============================================================
-- V17__app_user_profile_fields.sql
-- 扩展 app_user 表：补充用户资料字段（username 可空，email 仍是唯一登录键）。
-- 全部 ADD COLUMN IF NOT EXISTS，兼容已执行过部分字段的环境。
-- ============================================================

-- 登录名（可空：当前登录仍走 email，username 预留）
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS username VARCHAR(50);

-- 昵称
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS nickname VARCHAR(50);

-- 角色：USER / ADMIN
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS role VARCHAR(20) NOT NULL DEFAULT 'USER';

-- 性别：M / F / 空
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS gender VARCHAR(10);

-- 手机号
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS phone VARCHAR(20);

-- 生日
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS birthday DATE;

-- 头像 URL
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS avatar_url VARCHAR(255);

-- 状态：1 正常 / 0 禁用
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS status SMALLINT NOT NULL DEFAULT 1;

-- 扩展信息（JSON）
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS extra_info JSONB;

-- 最近登录时间
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS last_login_at TIMESTAMP;

-- 更新时间（配合触发器自动维护）
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;

-- ---------- updated_at ----------
-- 原为 PostgreSQL 触发器（调用 update_updated_at_column()）；H2 无 plpgsql，已移除。
-- app_user.updated_at 由数据库默认值维护（该字段非关键路径，前端未依赖实时更新）。
