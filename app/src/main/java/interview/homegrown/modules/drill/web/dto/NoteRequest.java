package interview.homegrown.modules.drill.web.dto;

/**
 * 内化笔记入参。
 *
 * <p>注意这个 record 的<b>缺席字段</b>比在场字段更重要：没有 summary、没有 correctAnswer、
 * 没有 aiExplanation。前端就算想给"一键保存标准答案"按钮，也没有字段可传。
 * 痛点 7 的解法不是提示用户"请用自己的话"，是让抄写在协议层就不成立。
 *
 * @param myWords    用自己的话复述（服务端做抄写检测 + 长度下限）
 * @param gapFound   这次暴露出的缺口（没过线时必填）
 * @param nextAction 下一步打算怎么补
 */
public record NoteRequest(
        String myWords,
        String gapFound,
        String nextAction
) {
}
