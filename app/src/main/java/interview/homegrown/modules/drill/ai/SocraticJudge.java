package interview.homegrown.modules.drill.ai;

/**
 * 苏格拉底每轮判定输出（AI 每轮对话后结构化返回）。
 *
 * <p>state 三态：
 * <ul>
 *   <li>answering：用户还在作答（澄清题意、没进入实质作答）→ AI 简短确认并等，不评分不引导</li>
 *   <li>needs_guide：用户已答完实质内容但未达标（覆盖<80% 或有致命缺漏）→ AI 抛一个引导问题（不给答案）</li>
 *   <li>done：达标（覆盖≥80% 且无致命缺漏）→ 表扬 + 提示结束；若 G1 未达标则触发再考查</li>
 * </ul>
 */
public record SocraticJudge(
        String state,           // answering / needs_guide / done
        double coverage,        // 评分点覆盖度 0~1
        boolean fatalGap,        // 是否有致命缺漏
        String guideQuestion,   // needs_guide 时下一步引导问题（不给答案）；其他态留空
        String praise            // done 时的表扬语；其他态留空
) {
    public enum State {
        ANSWERING, NEEDS_GUIDE, DONE;
        public static State of(String s) {
            return switch (s == null ? "" : s.toLowerCase()) {
                case "needs_guide", "needs-guide", "needsguide" -> NEEDS_GUIDE;
                case "done" -> DONE;
                default -> ANSWERING;
            };
        }
    }
}
