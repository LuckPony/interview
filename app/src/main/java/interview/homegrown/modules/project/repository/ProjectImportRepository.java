package interview.homegrown.modules.project.repository;

import interview.homegrown.modules.project.domain.ProjectImport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectImportRepository extends JpaRepository<ProjectImport, Long> {

    List<ProjectImport> findByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<ProjectImport> findByUserIdAndId(Long userId, Long id);

    Optional<ProjectImport> findByUserIdAndName(Long userId, String name);
}