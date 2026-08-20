package interview.homegrown.modules.drill.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * concept ↔ corpus_chunk 映射（联合主键，无自增 id，用 JdbcTemplate 轻量读写）。
 */
@Repository
public class ConceptChunkRepository {

    private final JdbcTemplate jdbc;

    public ConceptChunkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(Long conceptId, Long chunkId) {
        jdbc.update("INSERT INTO concept_chunk (concept_id, chunk_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                conceptId, chunkId);
    }

    public void deleteByConceptId(Long conceptId) {
        jdbc.update("DELETE FROM concept_chunk WHERE concept_id = ?", conceptId);
    }

    public List<Long> chunkIdsOfConcept(Long conceptId) {
        return jdbc.queryForList("SELECT chunk_id FROM concept_chunk WHERE concept_id = ? ORDER BY chunk_id",
                Long.class, conceptId);
    }

    /** 一个概念命中哪些资料块（join 取块实体字段）。 */
    public List<Long> chunkIdsOfConcepts(List<Long> conceptIds) {
        if (conceptIds == null || conceptIds.isEmpty()) return List.of();
        String in = String.join(",", java.util.Collections.nCopies(conceptIds.size(), "?"));
        return jdbc.queryForList("SELECT DISTINCT chunk_id FROM concept_chunk WHERE concept_id IN (" + in + ")",
                Long.class, conceptIds.toArray());
    }

    /** confirm 后建立映射：把概念名与资料块的知识点名（topic）做归一化匹配。 */
    public void mapConceptToChunksByTopic(Long conceptId, String conceptName, Long corpusId) {
        jdbc.update("""
                INSERT INTO concept_chunk (concept_id, chunk_id)
                SELECT ?, id FROM corpus_chunk
                WHERE corpus_id = ? AND topic IS NOT NULL
                  AND (lower(topic) = lower(?) OR lower(topic) LIKE lower(?) OR lower(?) LIKE lower(topic))
                ON CONFLICT DO NOTHING
                """, conceptId, corpusId, conceptName.trim(),
                "%" + conceptName.trim() + "%", "%" + conceptName.trim() + "%");
    }
}
