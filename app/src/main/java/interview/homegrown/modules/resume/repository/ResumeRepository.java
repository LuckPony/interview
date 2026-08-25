package interview.homegrown.modules.resume.repository;


import interview.homegrown.modules.resume.model.ResumeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface  ResumeRepository extends JpaRepository<ResumeEntity, Long> {

    //按内容哈希查简历（用于去重，限定同用户）
    Optional<ResumeEntity> findByUserIdAndContentHash(Long userId, String contentHash);

    //按内容哈希查简历（样例测试用；业务上去重限定同用户）
    Optional<ResumeEntity> findByContentHash(String contentHash);

    //当前用户的简历（按创建时间倒序）
    List<ResumeEntity> findByUserIdOrderByCreatedAtDesc(Long userId);

    //按创建时间倒序查全部简历（历史遗留，保留）
    List<ResumeEntity> findAllByOrderByCreatedAtDesc();


}
