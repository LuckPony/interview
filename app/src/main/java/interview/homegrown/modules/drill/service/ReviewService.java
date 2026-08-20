package interview.homegrown.modules.drill.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.homegrown.modules.drill.ai.ReviewGenerator;
import interview.homegrown.modules.drill.domain.DrillReview;
import interview.homegrown.modules.drill.domain.DrillRun;
import interview.homegrown.modules.drill.domain.DrillRunStatus;
import interview.homegrown.modules.drill.domain.DrillTurn;
import interview.homegrown.modules.drill.domain.GradeResult;
import interview.homegrown.modules.drill.domain.QuestionBank;
import interview.homegrown.modules.drill.repository.DrillReviewRepository;
import interview.homegrown.modules.drill.repository.DrillRunRepository;
import interview.homegrown.modules.drill.repository.DrillTurnRepository;
import interview.homegrown.modules.drill.repository.GradeResultRepository;
import interview.homegrown.modules.drill.repository.QuestionBankRepository;
import interview.homegrown.modules.drill.web.dto.ReviewView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * AI 复盘：对一道已判分作答生成「对话总结（欠缺） + 解题思路 + 记忆口诀」。
 * 结果按 runId 缓存（drill_review），同一道题只生成一次。
 */
@Service
public class ReviewService {

    private final DrillRunRepository runRepo;
    private final QuestionBankRepository qbRepo;
    private final GradeResultRepository gradeRepo;
    private final DrillTurnRepository turnRepo;
    private final DrillReviewRepository reviewRepo;
    private final ReviewGenerator reviewGenerator;
    private final ObjectMapper objectMapper;
    private final ProgressContextService progressContext;

    public ReviewService(DrillRunRepository runRepo, QuestionBankRepository qbRepo,
                         GradeResultRepository gradeRepo, DrillTurnRepository turnRepo,
                         DrillReviewRepository reviewRepo, ReviewGenerator reviewGenerator,
                         ObjectMapper objectMapper, ProgressContextService progressContext) {
        this.runRepo = runRepo;
        this.qbRepo = qbRepo;
        this.gradeRepo = gradeRepo;
        this.turnRepo = turnRepo;
        this.reviewRepo = reviewRepo;
        this.reviewGenerator = reviewGenerator;
        this.objectMapper = objectMapper;
        this.progressContext = progressContext;
    }

    @Transactional
    public ReviewView review(Long userId, Long runId) {
        DrillRun run = runRepo.findByUserIdAndId(userId, runId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "作答不存在"));
        if (run.getStatus() != DrillRunStatus.GRADED) {
            throw new ResponseStatusException(BAD_REQUEST, "只有判分完成的作答才有复盘");
        }

        GradeResult gr = gradeRepo.findByRunId(runId).orElse(null);
        double rawScore = gr == null || gr.getRawScore() == null ? 0 : gr.getRawScore().doubleValue();
        List<String> weakPoints = gr == null ? List.of() : extractWeakPoints(gr.getByConceptJson());

        DrillReview cached = reviewRepo.findById(runId).orElse(null);
        if (cached != null) {
            return toView(run, rawScore, weakPoints, cached);
        }

        QuestionBank q = qbRepo.findById(run.getQuestionId()).orElse(null);
        String pointsJson = q == null ? null : q.getPointsJson();
        String byConceptJson = gr == null ? null : gr.getByConceptJson();
        List<DrillTurn> turns = turnRepo.findByRunIdOrderByRoundAsc(runId);

        // 学习上下文（复盘可引用资料/互联网真实内容，不额外搜索）
        String context = null;
        if (q != null && q.getConceptIds() != null && q.getConceptIds().length > 0) {
            java.util.List<Long> ids = java.util.Arrays.stream(q.getConceptIds())
                    .map(Integer::longValue).toList();
            context = progressContext.contextFor(userId, ids);
        }

        ReviewGenerator.ReviewOutput out = reviewGenerator.generate(
                q == null ? "" : q.getStem(), pointsJson, byConceptJson, turns, context);

        DrillReview r = new DrillReview();
        r.setRunId(runId);
        r.setGapSummary(out.gapSummary());
        r.setApproach(out.approach());
        r.setMnemonic(out.mnemonic());
        reviewRepo.save(r);
        return toView(run, rawScore, weakPoints, r);
    }

    private ReviewView toView(DrillRun run, double rawScore, List<String> weakPoints, DrillReview r) {
        String stem = qbRepo.findById(run.getQuestionId())
                .map(QuestionBank::getStem).orElse("");
        return new ReviewView(run.getId(), stem, rawScore, weakPoints,
                r.getGapSummary(), r.getApproach(), r.getMnemonic());
    }

    /** 判分结果里没打中的评分点（MISS/PARTIAL），复盘页直接展示"哪里薄弱"。 */
    private List<String> extractWeakPoints(String byConceptJson) {
        if (byConceptJson == null || byConceptJson.isBlank()) return List.of();
        try {
            JsonNode root = objectMapper.readTree(byConceptJson);
            if (!root.isArray()) return List.of();
            List<String> weak = new ArrayList<>();
            for (JsonNode concept : root) {
                JsonNode prs = concept.path("pointResults");
                if (!prs.isArray()) continue;
                for (JsonNode p : prs) {
                    String verdict = p.path("verdict").asText("").toUpperCase();
                    if ("MISS".equals(verdict) || "PARTIAL".equals(verdict)) {
                        String point = p.path("point").asText("");
                        if (!point.isBlank()) weak.add(point);
                    }
                }
            }
            return weak;
        } catch (Exception e) {
            return List.of();
        }
    }
}
