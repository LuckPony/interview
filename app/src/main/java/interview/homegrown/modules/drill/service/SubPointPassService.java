package interview.homegrown.modules.drill.service;

import interview.homegrown.modules.drill.ai.LessonGenerator;
import interview.homegrown.modules.drill.domain.Concept;
import interview.homegrown.modules.drill.domain.QuestionBank;
import interview.homegrown.modules.drill.domain.SubPointPass;
import interview.homegrown.modules.drill.repository.ConceptRepository;
import interview.homegrown.modules.drill.repository.QuestionBankRepository;
import interview.homegrown.modules.drill.repository.SubPointPassRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 「答对自动通过子知识点」：按 L 层出题（或任意综合题）答对后（最终分 GOOD/EASY），
 * 把该题涉及的所有概念的<b>全部</b>子知识点（lessonOutline）写入 sub_point_pass，
 * 作为该概念子知识点「已通过」的依据（计划页自动点亮，无需用户手动点）。
 *
 * <p>只做并集、不删除：已存在的手动「直接通过」或历史通过记录保持不变；本次仅追加缺失项（幂等）。
 *
 * <p>子知识点清单：优先用缓存 lesson_outline；无缓存则现场拆解（与讲解页行为一致）并写回缓存，
 * 拆解失败时降级为该概念名本身（保证至少有一条通过记录）。
 */
@Service
public class SubPointPassService {

    private final QuestionBankRepository qbRepo;
    private final ConceptRepository conceptRepo;
    private final SubPointPassRepository passRepo;
    private final LessonGenerator lessonGenerator;
    private final ProgressContextService progressContext;

    public SubPointPassService(QuestionBankRepository qbRepo, ConceptRepository conceptRepo,
                               SubPointPassRepository passRepo, LessonGenerator lessonGenerator,
                               ProgressContextService progressContext) {
        this.qbRepo = qbRepo;
        this.conceptRepo = conceptRepo;
        this.passRepo = passRepo;
        this.lessonGenerator = lessonGenerator;
        this.progressContext = progressContext;
    }

    /**
     * 达标后把题目涉及的所有概念的全部子知识点标记为通过。
     *
     * @param userId      用户
     * @param runId       run（用于查 question）
     * @param questionId  题目
     */
    @Transactional
    public void markAllSubPointsPassed(Long userId, Long runId, Long questionId) {
        QuestionBank q = qbRepo.findById(questionId).orElse(null);
        if (q == null || q.getConceptIds() == null || q.getConceptIds().length == 0) return;

        for (Integer cid : q.getConceptIds()) {
            if (cid == null) continue;
            Concept c = conceptRepo.findById(cid.longValue()).orElse(null);
            if (c == null) continue;
            List<String> subPoints = ensureSubPoints(userId, c);
            if (subPoints.isEmpty()) continue;
            for (String sp : subPoints) {
                // 幂等：已存在（手动或本次）则跳过
                if (passRepo.findByUserIdAndConceptIdAndSubPoint(userId, c.getId(), sp).isPresent()) {
                    continue;
                }
                SubPointPass p = new SubPointPass();
                p.setUserId(userId);
                p.setConceptId(c.getId());
                p.setSubPoint(sp);
                passRepo.save(p);
            }
        }
    }

    /** 取概念的完整子知识点清单：缓存优先，无缓存现场拆解并写回（失败降级为概念名）。 */
    private List<String> ensureSubPoints(Long userId, Concept c) {
        List<String> cached = lessonGenerator.outlineFromJson(c.getLessonOutline());
        if (!cached.isEmpty()) return cached;

        // 无缓存：现场拆解（与讲解页 outline 端点一致），成功写回缓存
        String ctx = progressContext.contextFor(userId, c.getId());
        List<String> subPoints = lessonGenerator.decompose(c, ctx);
        if (subPoints.isEmpty()) {
            return List.of(c.getName());   // 降级：概念本身作为一个子点
        }
        String json = lessonGenerator.outlineToJson(subPoints);
        if (json != null) {
            c.setLessonOutline(json);
            conceptRepo.save(c);
        }
        return subPoints;
    }
}
