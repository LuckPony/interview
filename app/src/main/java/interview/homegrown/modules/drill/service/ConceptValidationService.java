package interview.homegrown.modules.drill.service;

import interview.homegrown.common.ai.LlmRawClient;
import interview.homegrown.modules.drill.web.dto.PlanPoint;
import interview.homegrown.modules.drill.web.dto.StudyPlanDraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 计划候选知识点的轻量联网核验。
 *
 * <p>第一版只回答“搜索是否找到相关内容”，不判断权威等级，也不自动否决内部术语；
 * 搜不到的点交给用户决定，避免把公司内部知识误判成幻觉。</p>
 */
@Service
public class ConceptValidationService {
    private static final Logger log = LoggerFactory.getLogger(ConceptValidationService.class);
    private static final int MAX_POINTS = 60;
    private static final int MAX_RESULT_CHARS = 1200;

    private final LlmRawClient rawClient;

    public ConceptValidationService(LlmRawClient rawClient) {
        this.rawClient = rawClient;
    }

    public ValidationResponse validate(StudyPlanDraft draft) {
        if (draft == null || draft.points() == null) return new ValidationResponse(List.of());
        List<PointValidation> results = draft.points().stream()
                .filter(p -> p != null && p.name() != null && !p.name().isBlank())
                .limit(MAX_POINTS)
                .map(this::validatePoint)
                .toList();
        return new ValidationResponse(results);
    }

    private PointValidation validatePoint(PlanPoint point) {
        String query = "请搜索技术知识点「" + point.name().trim()
                + "」，所属学习方向是「相关技术学习」，只返回与该知识点定义、原理、用法或常见问题相关的搜索结果摘要。";
        try {
            String result = rawClient.webSearch(query);
            if (result == null || result.isBlank()) {
                return new PointValidation(point.name(), "NOT_FOUND", "暂未搜索到相关内容", null);
            }
            return new PointValidation(point.name(), "FOUND", "已搜索到相关内容", truncate(result));
        } catch (Exception e) {
            log.warn("知识点核验失败 [{}]: {}", point.name(), e.getMessage());
            return new PointValidation(point.name(), "FAILED", "联网核验失败，不影响继续编辑", null);
        }
    }

    private String truncate(String text) {
        return text.length() <= MAX_RESULT_CHARS
                ? text : text.substring(0, MAX_RESULT_CHARS) + "…";
    }

    public record ValidationResponse(List<PointValidation> points) {}
    public record PointValidation(String name, String status, String message, String evidence) {}
}
