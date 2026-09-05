package interview.homegrown.modules.drill.repository;

import interview.homegrown.modules.drill.domain.Corpus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CorpusRepository extends JpaRepository<Corpus, Long> {
    List<Corpus> findByUserId(Long userId);

    List<Corpus> findByUserIdOrderByCreatedAtDesc(Long userId);
}
