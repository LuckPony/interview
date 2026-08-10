package interview.homegrown.modules.drill.ai;

/** 选择题选项（CHOICE 形态用，精确比对不走 LLM）。 */
public record McqOption(String key, String text, boolean correct) {
}
