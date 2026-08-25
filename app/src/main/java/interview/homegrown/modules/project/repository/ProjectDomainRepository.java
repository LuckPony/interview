package interview.homegrown.modules.project.repository;

import interview.homegrown.modules.project.domain.ProjectDomain;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProjectDomainRepository extends JpaRepository<ProjectDomain, Long> {

    List<ProjectDomain> findByProjectIdOrderBySortOrderAsc(Long projectId);

    void deleteByProjectId(Long projectId);
}