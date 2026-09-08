ALTER TABLE corpus ADD COLUMN IF NOT EXISTS overview TEXT;
ALTER TABLE corpus ADD COLUMN IF NOT EXISTS index_state VARCHAR(20) DEFAULT 'PENDING';
ALTER TABLE corpus ADD COLUMN IF NOT EXISTS original_key VARCHAR(255);
ALTER TABLE corpus ADD COLUMN IF NOT EXISTS original_type VARCHAR(255);
UPDATE corpus SET index_state = 'BASIC'
WHERE EXISTS (SELECT 1 FROM corpus_chunk cc WHERE cc.corpus_id = corpus.id);
ALTER TABLE interview_session ADD COLUMN IF NOT EXISTS corpus_id BIGINT REFERENCES corpus(id);
CREATE INDEX IF NOT EXISTS idx_interview_corpus ON interview_session(corpus_id);
