package interview.homegrown.modules.drill.repository;

import interview.homegrown.modules.drill.domain.DrillNote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DrillNoteRepository extends JpaRepository<DrillNote, Long> {

    Optional<DrillNote> findByRunId(Long runId);
}
