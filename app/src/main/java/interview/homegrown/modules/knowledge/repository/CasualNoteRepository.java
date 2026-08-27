package interview.homegrown.modules.knowledge.repository;

import interview.homegrown.modules.knowledge.domain.CasualNote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface CasualNoteRepository extends JpaRepository<CasualNote, Long> {
    List<CasualNote> findByUserIdOrderByCreatedAtDesc(Long userId);
    // Additional queries for fuzzy search can be added if needed, but we'll filter client-side for now.
}
