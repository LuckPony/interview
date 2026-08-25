package interview.homegrown.modules.project.repository;

import interview.homegrown.modules.project.domain.ProjectSubPoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProjectSubPointRepository extends JpaRepository<ProjectSubPoint, Long> {

    List<ProjectSubPoint> findByDomainIdOrderBySortOrderAsc(Long domainId);

    List<ProjectSubPoint> findByDomainIdIn(List<Long> domainIds);

    void deleteByDomainId(Long domainId);
}