-- DOCX 的标准 MIME 类型
-- application/vnd.openxmlformats-officedocument.wordprocessingml.document
-- 长于历史表定义的 VARCHAR(50)，上传 DOCX 时会在写入 resume 表阶段失败。
ALTER TABLE resume
    ALTER COLUMN file_type TYPE VARCHAR(255);
