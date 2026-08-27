package interview.homegrown.modules.drill.repository;

import interview.homegrown.modules.drill.domain.LessonQaMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface LessonQaRepository extends JpaRepository<LessonQaMessage, Long> {

    /** 某用户在某个子知识点下的全部答疑，按时间升序（用于回显与拼接 AI 上下文）。 */
    List<LessonQaMessage> findByUserIdAndConceptIdAndSubPointOrderByIdAsc(Long userId, Long conceptId, String subPoint);

    /** 删除用户在某个子知识点下的若干条答疑（仅自己的记录）。 */
    @Modifying
    @Transactional
    @Query("delete from LessonQaMessage m where m.userId = :userId and m.id in :ids")
    int deleteByIdsAndUser(@Param("userId") Long userId, @Param("ids") List<Long> ids);
}
