package interview.homegrown.modules.drill.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 文字触发答案揭示（wantsAnswerNow）的检测边界：命中 + 防误判。 */
class WantsAnswerNowTest {

    @Test
    void 直接要答案命中() {
        assertTrue(DrillController.wantsAnswerNow("直接给我答案"));
        assertTrue(DrillController.wantsAnswerNow("给我答案"));
        assertTrue(DrillController.wantsAnswerNow("直接告诉我答案吧"));
        assertTrue(DrillController.wantsAnswerNow("答案是什么"));
        assertTrue(DrillController.wantsAnswerNow("告诉我答案"));
        assertTrue(DrillController.wantsAnswerNow("给我完整答案"));
        assertTrue(DrillController.wantsAnswerNow("give me the answer"));
        assertTrue(DrillController.wantsAnswerNow("tell me the answer please"));
    }

    @Test
    void 放弃作答命中() {
        assertTrue(DrillController.wantsAnswerNow("我不会做"));
        assertTrue(DrillController.wantsAnswerNow("不会了"));
        assertTrue(DrillController.wantsAnswerNow("做不出来"));
        assertTrue(DrillController.wantsAnswerNow("想不出来"));
        assertTrue(DrillController.wantsAnswerNow("太难了"));
        assertTrue(DrillController.wantsAnswerNow("i don't know"));
        assertTrue(DrillController.wantsAnswerNow("i give up"));
    }

    @Test
    void 思考过程不误判() {
        // 「怎么做」可能只是思考/提问，不触发
        assertFalse(DrillController.wantsAnswerNow("这道题怎么做"));
        assertFalse(DrillController.wantsAnswerNow("如何实现这个功能"));
        assertFalse(DrillController.wantsAnswerNow("怎么用channel实现互斥"));
        // 正常作答过程不触发
        assertFalse(DrillController.wantsAnswerNow("我先声明一个channel，然后用select等待"));
        assertFalse(DrillController.wantsAnswerNow("我认为应该用锁，但不确定"));
        // 「不会做但想试试」→ 想尝试而非放弃，不触发（即便含“我不会做”前缀）
        assertFalse(DrillController.wantsAnswerNow("我不会做，但我想先试试这样写：先建一个结构体，然后定义方法……"));
        assertFalse(DrillController.wantsAnswerNow("我不会做，但我想先试试用channel写"));
    }

    @Test
    void 边界情形() {
        assertFalse(DrillController.wantsAnswerNow(""));
        assertFalse(DrillController.wantsAnswerNow(null));
        assertFalse(DrillController.wantsAnswerNow("   "));
    }
}
