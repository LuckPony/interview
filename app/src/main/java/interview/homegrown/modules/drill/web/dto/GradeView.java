package interview.homegrown.modules.drill.web.dto;

/** 判分响应：服务端算出的 rawScore + FSRS 档 + 统一 byConcept 明细。
 * transferExhausted 仅由补救测试判分（gradeTransfer）设置，普通判分/abandon 默认 false。 */
public record GradeView(Long runId, Long questionId, double rawScore, String grade, String byConceptJson,
                        boolean transferExhausted) {
    public GradeView(Long runId, Long questionId, double rawScore, String grade, String byConceptJson) {
        this(runId, questionId, rawScore, grade, byConceptJson, false);
    }
}
