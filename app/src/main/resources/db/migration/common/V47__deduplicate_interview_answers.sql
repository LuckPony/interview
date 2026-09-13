-- 旧版在“结束答题”和“开始评估”两个阶段都可能重复落库同一批问答。
-- 保留最早写入的一条，只删除题号、题目、答案与追问标识全部相同的确定性重复行。
DELETE FROM interview_answer
WHERE id IN (
    SELECT id
    FROM (
        SELECT id,
               ROW_NUMBER() OVER (
                   PARTITION BY session_id,
                                question_index,
                                question_text,
                                COALESCE(answer_text, ''),
                                is_follow_up
                   ORDER BY id
               ) AS duplicate_rank
        FROM interview_answer
    ) ranked_answers
    WHERE duplicate_rank > 1
);
