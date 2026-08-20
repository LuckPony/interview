package interview.homegrown.modules.drill.repository;

import interview.homegrown.modules.drill.domain.CorpusChunk;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CorpusChunkRepository extends JpaRepository<CorpusChunk, Long> {

    List<CorpusChunk> findByCorpusIdOrderBySeqAsc(Long corpusId);

    void deleteByCorpusId(Long corpusId);

    long countByCorpusId(Long corpusId);
}
