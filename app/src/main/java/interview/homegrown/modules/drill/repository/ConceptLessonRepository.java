package interview.homegrown.modules.drill.repository;

import interview.homegrown.modules.drill.domain.ConceptLesson;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConceptLessonRepository extends JpaRepository<ConceptLesson, Long> {

    Optional<ConceptLesson> findByConceptIdAndSubPoint(Long conceptId, String subPoint);

    /** 某知识点下所有子知识点的讲解缓存（用于「讲解避重」：生成一个新子点讲解时参考兄弟点已讲内容）。 */
    List<ConceptLesson> findByConceptId(Long conceptId);

    /** 删除某个子知识点的讲解缓存（删除子知识点时同步清理）。 */
    void deleteByConceptIdAndSubPoint(Long conceptId, String subPoint);
}
