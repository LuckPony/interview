-- 删除废弃的 users 表（历史遗留：与 app_user 重复，曾由 V29 恢复，现已无用）。
-- 注意：update_updated_at_column() 函数仍被 app_user/concept 等多表触发器使用，不可删除。
DROP TABLE IF EXISTS users;
