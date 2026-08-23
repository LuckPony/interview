-- AI 复盘缓存：每个已判分作答一份「复盘报告」。
-- gap_summary：对话总结 + 欠缺点；approach：解题思路；mnemonic：记忆口诀。
-- 生成一次缓存，之后打开直接读，不再重复调 LLM。
CREATE TABLE IF NOT EXISTS drill_review (
    run_id       BIGINT PRIMARY KEY REFERENCES drill_run(id),
    gap_summary  TEXT,
    approach     TEXT,
    mnemonic     TEXT,
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
