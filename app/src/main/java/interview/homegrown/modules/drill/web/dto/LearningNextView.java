package interview.homegrown.modules.drill.web.dto;

/** “继续学习”的确定性下一步：按 L1→L5、概念、子知识点、综合检测依次推进。 */
public record LearningNextView(
        Long planId,
        String planTitle,
        String stepType,       // SUB_POINT / CONCEPT_ASSESSMENT / LEVEL_ASSESSMENT / COMPLETE
        int layer,
        Long conceptId,
        String conceptName,
        String subPoint,
        int subPointIndex,
        int subPointTotal,
        int assessmentDone,
        int assessmentRequired,
        String message
) {
}
