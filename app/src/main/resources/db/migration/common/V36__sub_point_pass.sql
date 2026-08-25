-- 用户手动「直接通过」的子知识点：简单知识点无需做题，点讲解页的通过按钮即视为达标。
-- completedSubPoints = 判分通过(≥及格线)的练习 run ∪ 手动通过的记录；取消通过即删除本行。
CREATE TABLE IF NOT EXISTS sub_point_pass (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT       NOT NULL,
    concept_id BIGINT       NOT NULL,
    sub_point  VARCHAR(500) NOT NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_sub_point_pass UNIQUE (user_id, concept_id, sub_point)
);
CREATE INDEX IF NOT EXISTS idx_sub_point_pass_user ON sub_point_pass(user_id);
