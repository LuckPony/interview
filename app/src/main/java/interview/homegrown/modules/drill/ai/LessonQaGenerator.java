package interview.homegrown.modules.drill.ai;

import interview.homegrown.common.ai.LlmRawClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Consumer;

/**
 * 子知识点讲解页的答疑生成器（用户 2026-08 决策：讲解页只答疑，不反哺讲解正文）。
 *
 * <p>与 {@link LessonGenerator}（讲）、{@link TutorGenerator#streamChat}（做题对话）解耦：
 * 答疑只发生在讲解页、只围绕「当前子知识点的讲解」，结合该用户自己的学习上下文回答。
 * 答疑不判分、不进 run、不动 mastery、不反哺讲解。上下文注入顺序：
 * 讲解原文 + 用户选中的片段（anchor）+ 学习上下文（学生画像/概念要点/资料块/互联网补充）
 * + 该用户在此子点下的最近答疑历史，让 AI 知道已经讲过什么、避免重复。
 */
@Component
public class LessonQaGenerator {

    private static final Logger log = LoggerFactory.getLogger(LessonQaGenerator.class);

    private static final int MAX_LESSON_CHARS = 6000;
    private static final int MAX_CONTEXT_CHARS = 6000;
    private static final int MAX_HISTORY_CHARS = 3000;
    private static final int MAX_ANCHOR_CHARS = 1000;

    private final LlmRawClient rawClient;

    public LessonQaGenerator(LlmRawClient rawClient) {
        this.rawClient = rawClient;
    }

    private static final String SYSTEM_PROMPT = """
            你是一位耐心、有经验的技术老师，正在一对一辅导学生阅读一段知识点讲解。
            学生针对「当前这段讲解」提出了疑问，请你准确回答他的问题。
            要求：
            1. 只围绕当前子知识点的讲解内容作答；先直接回答学生问的点，再视需要补充一句白话解释
            2. 结合讲解原文与学生的学习上下文作答；引用资料内容时可注明出处
            3. 学生选中的那一段讲解（anchor）就是他卡住或想深究的地方，优先针对它讲清
            4. 是否给代码完全由问题决定，不要默认让学生写代码：
               - 纯概念、原理、区别类问题：直接讲清楚，不要求用户动手写代码
               - 涉及程序行为、API、配置、数据结构或实现方式：由你给出最小代码/配置示例并放在围栏代码块
                 （```语言 ... ```，原样保留缩进与换行），示例是讲给你听的，不是布置作业
               - 学生在问自己的代码 bug / 想补全某段代码：直接帮他定位问题、补全或纠正那一段，不要反过来让他先写
               - 涉及调用链、生命周期、架构、状态流转时可给 ```mermaid 图
            5. 只依据确切的技术事实回答。某个点不完全确定就直说“这点我不完全确定，建议你用 xxx 验证”，
               绝不编造，也不强行补充无关边界
            6. 回复 100-300 字中文，优先白话和最小示例；不要使用中文破折号（——/-）
            7. 用 Markdown 排版（加粗关键点、必要时列表）；结尾必须是一句完整的话
            8. 你只是一对一答疑，不要判分、不要追问“理解了吗”、不要诱导学生复述、不要布置练习，直接解决问题即可
            """;

    /**
     * 流式答疑：逐 token 回调 onToken；返回累积完整文本（失败/空为 null）。
     * onReasoning 可选：模型思考内容独立回调。
     *
     * @param conceptId   概念 id（仅用于定位，prompt 中由 name/subPoint 表述）
     * @param conceptName 概念名
     * @param topic       概念主题
     * @param subPoint    子知识点名
     * @param lessonText  当前讲解正文（可空，为空时提示 AI 没有讲解）
     * @param anchor      学生选中的讲解片段（可空）
     * @param context     该用户自己的学习上下文（ProgressContextService 装配）
     * @param history     该用户在此子点下的最近答疑（问答对，按时间升序）
     * @param question    学生本次提问
     */
    public String streamAnswer(String conceptName, String topic, int layer,
                               String subPoint, String lessonText, String anchor,
                               String context, List<QaPair> history, String question,
                               Consumer<String> onToken, Consumer<String> onReasoning) {
        StringBuilder user = new StringBuilder(String.format("""
                概念：%s（主题：%s，认知层 L%d）
                子知识点：%s
                """, conceptName, topic, layer, subPoint));

        if (lessonText != null && !lessonText.isBlank()) {
            user.append("\n\n当前讲解正文：\n").append(truncate(lessonText, MAX_LESSON_CHARS));
        } else {
            user.append("\n\n（当前没有讲解正文，学生直接在问这个子知识点）");
        }

        if (anchor != null && !anchor.isBlank()) {
            user.append("\n\n学生选中的讲解片段（他卡住或想深究的地方）：\n")
                    .append(truncate(anchor, MAX_ANCHOR_CHARS));
        }

        if (context != null && !context.isBlank()) {
            user.append("\n\n学生的学习上下文（学生进度 / 概念要点 / 用户上传资料 / 互联网补充，作参考素材，引用可注明出处）：\n")
                    .append(truncate(context, MAX_CONTEXT_CHARS));
        }

        if (history != null && !history.isEmpty()) {
            StringBuilder h = new StringBuilder();
            for (QaPair p : history) {
                h.append("学生：").append(truncate(p.question(), 300)).append('\n');
                if (p.answer() != null && !p.answer().isBlank()) {
                    h.append("老师：").append(truncate(p.answer(), 500)).append('\n');
                }
                if (h.length() >= MAX_HISTORY_CHARS) break;
            }
            if (!h.isEmpty()) {
                user.append("\n\n该子知识点下你们此前的答疑（回答时避免重复，若学生问到已答过的问题可直接引用）：\n")
                        .append(h);
            }
        }

        user.append("\n\n学生的提问：").append(question);
        user.append("\n\n请回答学生。");

        StringBuilder buf = new StringBuilder();
        rawClient.stream(SYSTEM_PROMPT, user.toString(),
                token -> {
                    buf.append(token);
                    try {
                        onToken.accept(token);
                    } catch (Exception e) {
                        log.debug("lesson-qa onToken 回调异常（已吞）: {}", e.getMessage());
                    }
                },
                err -> log.warn("讲解答疑流式生成失败: {}", err.getMessage()),
                /* fallbackToReasoning */ false,
                onReasoning == null ? null : r -> {
                    try {
                        onReasoning.accept(r);
                    } catch (Exception ignored) {
                    }
                });
        String text = buf.toString().trim();
        return text.isEmpty() ? null : text;
    }

    /** 一次答疑的问答对（供 AI 记住已答过的内容）。 */
    public record QaPair(String question, String answer) {}

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max) + "…（截断）";
    }
}
