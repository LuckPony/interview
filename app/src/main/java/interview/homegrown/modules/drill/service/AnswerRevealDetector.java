package interview.homegrown.modules.drill.service;

import java.util.List;

/**
 * 答案揭示请求识别：判断学生某条聊天消息是否在<b>明确索要答案/提示</b>。
 *
 * <p>用途：划定「得到答案之前」的评分边界（痛点见 V14 迁移与 {@code GradingService#finish}）。
 * 一旦命中，chat 端点把该轮记为 run.answerRevealedRound，AI 转入完整讲解模式，
 * 之后的用户回答不再计入量化评分。
 *
 * <p>设计：两级信号 + 是非确认问句豁免。
 * <ul>
 *   <li>STRONG：高置信索要（"告诉我答案""答案是什么""我不会""给点提示"…），命中即索要；</li>
 *   <li>WEAK：低置信（"告诉我""思路""不会"…），若整句是<b>是非确认问句</b>
 *       （"我的思路对吗""这样不会死锁吗"）则豁免 —— 那是求证自己的答案，不是索要答案；</li>
 * </ul>
 * 纯启发式、可确定性单测、零额外 LLM 调用。宁可多触发（提前关闭评分窗口）也
 * 不要漏触发（把复述当独立思考计入分数）。主要通路仍是前端「看答案」按钮（显式标记），
 * 本检测器只是兜底自然语言输入。
 */
public final class AnswerRevealDetector {

    private AnswerRevealDetector() {
    }

    /** 高置信索要信号：命中即视为索要答案/提示 */
    private static final List<String> STRONG = List.of(
            "告诉我答案", "答案是什么", "答案呢", "答案给我", "给我答案", "答案是什么呀", "答案是什么啊",
            "直接告诉我", "直接说答案", "直接给答案", "直接给我",
            "看答案", "标准答案", "参考答案", "公布答案", "揭晓",
            "教教我", "我不会", "不会做", "不会写", "不会了", "完全不会",
            "没思路", "无从下手", "想不出来", "做不出来", "写不出来",
            "给点提示", "给个提示", "提示一下", "提示我", "给提示", "一点提示",
            "怎么解", "怎么实现", "怎么答", "怎么做", "怎么处理", "如何实现",
            "解法", "讲解一下", "讲讲", "讲一下", "解析", "hint"
    );

    /** 低置信信号：若整句是是非确认问句则豁免 */
    private static final List<String> WEAK = List.of(
            "告诉我", "直接说", "提示", "思路", "教我", "教一下", "不会"
    );

    /** 是非确认问句结尾（去掉标点后以这些词收尾 → 是求证，不是索要） */
    private static final List<String> VALIDATION_SUFFIX = List.of(
            "吗", "么", "对不对", "对吗", "对吧", "行不行", "好不好", "会不会", "合理吗", "正确吗"
    );

    public static boolean isRevealRequest(String text) {
        if (text == null || text.isBlank()) return false;
        String s = text.toLowerCase().trim();
        for (String marker : STRONG) {
            if (s.contains(marker)) return true;
        }
        for (String marker : WEAK) {
            if (s.contains(marker)) {
                String tail = s.replaceAll("[\\s。！？!?.,，；;~～…、]+$", "");
                if (endsWithAny(tail, VALIDATION_SUFFIX)) return false;
                return true;
            }
        }
        return false;
    }

    private static boolean endsWithAny(String s, List<String> suffixes) {
        for (String sfx : suffixes) {
            if (s.endsWith(sfx)) return true;
        }
        return false;
    }
}
