package interview.homegrown.modules.drill;

import interview.homegrown.modules.drill.service.AnswerRevealDetector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 答案揭示请求识别回归锁：划定「得到答案之前」的评分边界。
 *
 * <p>关键权衡：宁可多触发（提前关闭评分窗口）也不要漏触发（把照抄答案复述当独立思考计分）。
 * 但<b>是非确认问句</b>（"我的思路对吗""这样不会死锁吗"）是求证自己的答案，不是索要答案，
 * 必须豁免，否则用户正常追问会被误判为已揭示、把自己的回答挡在评分之外。
 */
class AnswerRevealDetectorTest {

    @Test
    void 明确索要答案应命中() {
        assertTrue(AnswerRevealDetector.isRevealRequest("这道题的答案是什么"));
        assertTrue(AnswerRevealDetector.isRevealRequest("告诉我答案"));
        assertTrue(AnswerRevealDetector.isRevealRequest("我不会做"));
        assertTrue(AnswerRevealDetector.isRevealRequest("给点提示呗"));
        assertTrue(AnswerRevealDetector.isRevealRequest("这题怎么实现？"));
        assertTrue(AnswerRevealDetector.isRevealRequest("没思路，讲讲吧"));
        assertTrue(AnswerRevealDetector.isRevealRequest("完全不会，教教我"));
    }

    @Test
    void 是非确认问句不应命中() {
        assertFalse(AnswerRevealDetector.isRevealRequest("我的思路对吗"));
        assertFalse(AnswerRevealDetector.isRevealRequest("我的答案对吗"));
        assertFalse(AnswerRevealDetector.isRevealRequest("这样不会死锁吗"));
        assertFalse(AnswerRevealDetector.isRevealRequest("我这样做可以吗"));
        assertFalse(AnswerRevealDetector.isRevealRequest("用 HashMap 行不行"));
    }

    @Test
    void 普通作答不应命中() {
        assertFalse(AnswerRevealDetector.isRevealRequest("我认为应该用 ConcurrentHashMap，因为要保证并发下的原子性"));
        assertFalse(AnswerRevealDetector.isRevealRequest("先加锁再读，最后释放"));
        assertFalse(AnswerRevealDetector.isRevealRequest(""));
        assertFalse(AnswerRevealDetector.isRevealRequest(null));
    }
}
