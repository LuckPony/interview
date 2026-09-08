package interview.homegrown.modules.drill.repository;

import interview.homegrown.modules.drill.domain.Corpus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import java.util.Optional;

import java.util.List;

public interface CorpusRepository extends JpaRepository<Corpus, Long> {
    List<Corpus> findByUserId(Long userId);

    List<Corpus> findByUserIdOrderByCreatedAtDesc(Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Corpus> findLockedById(Long id);
}
