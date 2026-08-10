package interview.homegrown.modules.drill.web.dto;

/** 判分响应：服务端算出的 rawScore + FSRS 档 + 统一 byConcept 明细。 */
public record GradeView(Long runId, Long questionId, double rawScore, String grade, String byConceptJson) {
}
