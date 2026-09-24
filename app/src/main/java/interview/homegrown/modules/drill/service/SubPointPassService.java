package interview.homegrown.modules.drill.service;

import interview.homegrown.modules.drill.ai.LessonGenerator;
import interview.homegrown.modules.drill.domain.Concept;
import interview.homegrown.modules.drill.domain.DrillRun;
import interview.homegrown.modules.drill.domain.QuestionBank;
import interview.homegrown.modules.drill.domain.SubPointPass;
import interview.homegrown.modules.drill.repository.ConceptRepository;
import interview.homegrown.modules.drill.repository.QuestionBankRepository;
import interview.homegrown.modules.drill.repository.DrillRunRepository;
import interview.homegrown.modules.drill.repository.SubPointPassRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static interview.homegrown.modules.drill.grader.GradeScale.PASS_LINE;

/**
 * 「答对自动通过子知识点」：答对达标（最终分 GOOD/EASY）后，把通过记录写入 sub_point_pass，
 * 作为该子知识点「已通过」的依据（计划页自动点亮，无需用户手动点）。
 *
 * <p>按 run 是否聚焦子点分流：
 * <ul>
 *   <li><b>聚焦子点练习</b>（{@code run.focusSubPoint} 非空）：只通过本次作答聚焦的那一个子点，
 *       答对一题只证明该子点已掌握，不该把整个大知识点全点亮。</li>
 *   <li><b>综合题</b>（{@code run.focusSubPoint} 为空）：题涉概念的<b>全部</b>子知识点视为通过。</li>
 * </ul>
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
    private final DrillRunRepository runRepo;
    private final LessonGenerator lessonGenerator;
    private final ProgressContextService progressContext;

    public SubPointPassService(QuestionBankRepository qbRepo, ConceptRepository conceptRepo,
                               SubPointPassRepository passRepo, DrillRunRepository runRepo,
                               LessonGenerator lessonGenerator,
                               ProgressContextService progressContext) {
        this.qbRepo = qbRepo;
        this.conceptRepo = conceptRepo;
        this.passRepo = passRepo;
        this.runRepo = runRepo;
        this.lessonGenerator = lessonGenerator;
        this.progressContext = progressContext;
    }

    /**
     * 达标后标记子知识点为通过。按 run 是否聚焦子点分流：
     *
     * <ul>
     *   <li><b>聚焦子点练习</b>（{@code run.focusSubPoint} 非空）：只把「本次作答聚焦的那个子知识点」标记为通过，
     *       不碰该概念的其它子点。答对一题只证明<b>这一个</b>子点已掌握，不能因此把整个大知识点全点亮。</li>
     *   <li><b>综合题</b>（{@code run.focusSubPoint} 为空，如 CONCEPT/LEVEL_ASSESSMENT、按知识点/整层出题）：
     *       一道综合题覆盖多个子点，答对即视为题涉概念的<b>全部</b>子知识点通过。</li>
     * </ul>
     *
     * @param userId      用户
     * @param run         本次作答（用 {@code focusSubPoint} 判断是否为聚焦子点练习）
     * @param questionId  题目
     */
    @Transactional
    public void markAllSubPointsPassed(Long userId, DrillRun run, Long questionId) {
        QuestionBank q = qbRepo.findById(questionId).orElse(null);
        if (q == null || q.getConceptIds() == null || q.getConceptIds().length == 0) return;

        String focus = run.getFocusSubPoint();
        if (focus != null && !focus.isBlank()) {
            // 聚焦子点练习：只通过「本次作答聚焦的那一个子知识点」，不能让整题概念全部点亮。
            // 该子点属于主概念（conceptIds[0]，恒为 PRIMARY），直接幂等写入即可。
            // 完成判定本身由 findPassedFocusedRuns（run.focusSubPoint + 达标分）兜底，
            // 这里写一条持久记录，避免 run 被清理后丢失完成态。
            Long primary = q.getConceptIds()[0] == null ? null : q.getConceptIds()[0].longValue();
            if (primary != null) markPassed(userId, primary, focus);
            return;
        }

        // 综合题（如 CONCEPT/LEVEL_ASSESSMENT、按知识点/整层出题）：一道题覆盖多个子点，
        // 答对即视为题涉概念的<b>全部</b>子知识点通过（幂等：已存在则跳过）。
        for (Integer cid : q.getConceptIds()) {
            if (cid == null) continue;
            Concept c = conceptRepo.findById(cid.longValue()).orElse(null);
            if (c == null) continue;
            List<String> subPoints = ensureSubPoints(userId, c);
            if (subPoints.isEmpty()) continue;
            for (String sp : subPoints) {
                markPassed(userId, c.getId(), sp);
            }
        }
    }

    /** 幂等写通过记录：已存在（手动或历史）则跳过。 */
    private void markPassed(Long userId, Long conceptId, String subPoint) {
        if (passRepo.findByUserIdAndConceptIdAndSubPoint(userId, conceptId, subPoint).isPresent()) {
            return;
        }
        SubPointPass p = new SubPointPass();
        p.setUserId(userId);
        p.setConceptId(conceptId);
        p.setSubPoint(subPoint);
        passRepo.save(p);
    }

    /** 取概念的完整子知识点清单：缓存优先，无缓存现场拆解并写回（失败降级为概念名）。 */
    private List<String> ensureSubPoints(Long userId, Concept c) {
        List<String> subs = lessonGenerator.ensureOutline(c, progressContext.contextFor(userId, c.getId()));
        conceptRepo.save(c);
        return subs;
    }

    /**
     * 计算用户已通过的子知识点：判分通过（≥及格线的聚焦 run）∪ 手动直通。
     * 返回 conceptId → 已通过子点集合。供计划页 / outline / 复习选点共用。
     */
    public Map<Long, Set<String>> passedSubPoints(Long userId) {
        Map<Long, Set<String>> result = new HashMap<>();
        runRepo.findPassedFocusedRuns(userId,
                        interview.homegrown.modules.drill.domain.DrillRunStatus.GRADED, PASS_LINE)
                .forEach(run -> {
                    if (run.getFocusSubPoint() == null) return;
                    qbRepo.findById(run.getQuestionId()).ifPresent(q -> {
                        if (q.getConceptIds() == null || q.getConceptIds().length == 0) return;
                        Integer cid = q.getConceptIds()[0];
                        if (cid != null) {
                            result.computeIfAbsent(cid.longValue(), k -> new HashSet<>())
                                    .add(run.getFocusSubPoint());
                        }
                    });
                });
        passRepo.findByUserId(userId).forEach(p ->
                result.computeIfAbsent(p.getConceptId(), k -> new HashSet<>()).add(p.getSubPoint()));
        return result;
    }
}
