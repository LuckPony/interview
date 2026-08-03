package interview.homegrown.modules.resume.repository;


import interview.homegrown.modules.resume.model.ResumeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface  ResumeRepository extends JpaRepository<ResumeEntity, Long> {

    //按内容哈希查简历（用于去重）
    Optional<ResumeEntity> findByContentHash(String contentHash);

    //按创建时间倒序查全部简历
    List<ResumeEntity> findAllByOrderByCreatedAtDesc();
}
