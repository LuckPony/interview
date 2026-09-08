package interview.homegrown.modules.drill.repository;

import interview.homegrown.modules.drill.domain.CorpusChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface CorpusChunkRepository extends JpaRepository<CorpusChunk, Long> {

    List<CorpusChunk> findByCorpusIdOrderBySeqAsc(Long corpusId);

    void deleteByCorpusId(Long corpusId);

    long countByCorpusId(Long corpusId);

    interface TopicRow { Long getCorpusId(); String getTopic(); String getTitle(); }

    @Query("select c.corpusId as corpusId, c.topic as topic, c.title as title from CorpusChunk c where c.corpusId in :ids order by c.seq")
    List<TopicRow> findTopicsByCorpusIds(List<Long> ids);
}
