-- 项目导入学习计划：用户上传任意项目源码，自动分析业务域、生成学习计划。
-- project_import：导入记录（技术栈 + 状态）
-- project_domain：剖析出的业务域（对应知识点）
-- project_sub_point：每个域的机制/子模块（对应子知识点）

CREATE TABLE IF NOT EXISTS project_import (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    name        VARCHAR(200) NOT NULL,          -- 项目名（来自文件夹名或 zip 名）
    tech_stack  TEXT,                            -- 检测到的技术栈（JSON 数组字符串）
    root_path   TEXT         NOT NULL,           -- 导入后解压/保存的本地路径
    status      VARCHAR(20)  NOT NULL DEFAULT 'PENDING',  -- PENDING / ANALYZING / READY / FAILED
    error_msg   TEXT,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uni_project_import_user UNIQUE (user_id, name)
);
CREATE INDEX IF NOT EXISTS idx_project_import_user ON project_import (user_id);

CREATE TABLE IF NOT EXISTS project_domain (
    id          BIGSERIAL PRIMARY KEY,
    project_id  BIGINT       NOT NULL REFERENCES project_import(id) ON DELETE CASCADE,
    name        VARCHAR(200) NOT NULL,           -- 业务域名（如「对话式辅导」「判分引擎」）
    overview    TEXT,
    sort_order  INT          NOT NULL DEFAULT 0,
    ref_files   TEXT,                            -- 参考文件清单（JSON 数组字符串）
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_project_domain_project ON project_domain (project_id);

CREATE TABLE IF NOT EXISTS project_sub_point (
    id          BIGSERIAL PRIMARY KEY,
    domain_id   BIGINT       NOT NULL REFERENCES project_domain(id) ON DELETE CASCADE,
    name        VARCHAR(300) NOT NULL,           -- 子知识点名
    description TEXT,                            -- 项目专属描述
    ref_files   TEXT,                            -- 参考文件清单（JSON 数组字符串）
    sort_order  INT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_project_sub_point_domain ON project_sub_point (domain_id);