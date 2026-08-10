package interview.homegrown.modules.drill;

import interview.homegrown.modules.drill.service.NgramSimilarityGuard;
import interview.homegrown.modules.drill.service.SimilarityGuard;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 抄写检测的语料标定与回归锁。
 *
 * <p>阈值不能拍脑袋。在真实语料上跑出来的数值是：
 * <pre>
 *                 containment   Jaccard
 *   整段照抄          1.000       0.518
 *   自己的话          0.020       0.009
 *   夹带一半          0.603       0.255
 * </pre>
 *
 * <p>两个结论：
 * <ol>
 *   <li>containment 把三档拉开到 0.02 / 0.60 / 1.00，阈值 0.35 正好落在
 *       "自己的话"与"夹带"之间的巨大空隙里，两侧各有 0.25+ 的余量，怎么抖都不会误判。</li>
 *   <li>Jaccard 完全不能用：整段照抄才 0.518，夹带才 0.255 —— 想抓夹带就得把阈值压到 0.25，
 *       而那个位置离正常笔记太近，随便一篇长一点的笔记就误杀。<b>这不是调参能救的，
 *       是指标选错了。</b></li>
 * </ol>
 */
class SimilarityGuardTest {

    /** 与 NoteService.COPY_THRESHOLD 保持一致 */
    private static final double THRESHOLD = 0.35;

    private final SimilarityGuard guard = new NgramSimilarityGuard();

    private static final String STEM =
            "请解释 JVM 的双亲委派模型：类加载器收到加载请求时会先委派给父加载器，"
                    + "只有当父加载器无法完成加载时才由自己尝试加载。说明它解决了什么问题。";

    private static final List<String> POINTS = List.of(
            "类加载器收到请求先向上委派给父加载器",
            "父加载器无法完成时子加载器才尝试加载",
            "保证核心类库不被应用层同名类覆盖，避免安全问题",
            "保证同一个类在 JVM 中的唯一性"
    );

    /** 场景 A：整段照抄题干 */
    private static final String COPIED =
            "双亲委派模型：类加载器收到加载请求时会先委派给父加载器，"
                    + "只有当父加载器无法完成加载时才由自己尝试加载。保证核心类库不被应用层同名类覆盖。";

    /** 场景 B：真·用自己的话（术语必然复用，但句式和视角是自己的） */
    private static final String OWN_WORDS =
            "我的理解是儿子遇事先问爹，爹搞不定才轮到自己动手。这么设计不是为了省事，"
                    + "而是怕有人写个假的 java.lang.String 塞进来把核心库顶掉。"
                    + "顺带的好处是同一个类不会在内存里出现两份，equals 才不会莫名其妙返回 false。";

    /** 场景 C：夹带 —— 自己写一半，从评分点搬一半，这是最常见的自欺方式 */
    private static final String MIXED =
            "我的理解是儿子遇事先问爹，爹搞不定才轮到自己动手。"
                    + "保证核心类库不被应用层同名类覆盖，避免安全问题。保证同一个类在 JVM 中的唯一性。";

    /**
     * 场景 D：误杀边界 —— 术语极度密集但确实是自己组织的短笔记。
     * 这类文本最容易被抄写检测冤枉，必须放行。
     */
    private static final String TERM_DENSE =
            "关键词：双亲委派、父加载器、核心类库。我记的是一条链而不是一句话："
                    + "请求往上走，加载往下落。链断在哪一层，哪一层就说了算。";

    private List<String> sources() {
        List<String> s = new ArrayList<>(POINTS);
        s.add(STEM);
        return s;
    }

    @Test
    void 整段照抄必须被拦下() {
        double v = guard.containmentOfAll(COPIED, sources());
        System.out.printf("A 整段照抄: %.3f%n", v);
        assertTrue(v > THRESHOLD, "整段照抄居然没超阈值: " + v);
    }

    @Test
    void 夹带评分点必须被拦下() {
        double v = guard.containmentOfAll(MIXED, sources());
        System.out.printf("C 夹带一半: %.3f%n", v);
        assertTrue(v > THRESHOLD, "夹带评分点没被抓到: " + v);
    }

    @Test
    void 自己的话必须放行() {
        double v = guard.containmentOfAll(OWN_WORDS, sources());
        System.out.printf("B 自己的话: %.3f%n", v);
        assertTrue(v < THRESHOLD, "真·自己的话被误杀了: " + v);
    }

    @Test
    void 术语密集的短笔记不能误杀() {
        double v = guard.containmentOfAll(TERM_DENSE, sources());
        System.out.printf("D 术语密集: %.3f%n", v);
        assertTrue(v < THRESHOLD, "术语复用被当成抄写: " + v);
    }

    /**
     * 反证：同一批语料换成 Jaccard，"整段照抄"与"夹带"双双掉到阈值以下。
     * 这条测试存在的意义不是测代码，是<b>锁住选型理由</b> —— 将来有人想图省事
     * 把抄写检测改回 similarity()，这里会立刻红给他看。
     */
    @Test
    void Jaccard不适合做抄写检测() {
        String merged = String.join("\n", sources());
        double copied = guard.similarity(COPIED, merged);
        double mixed = guard.similarity(MIXED, merged);
        System.out.printf("Jaccard 照抄=%.3f 夹带=%.3f%n", copied, mixed);
        assertTrue(copied < 0.6 && mixed < THRESHOLD,
                "若 Jaccard 也能拉开差距，本项目关于 containment 的选型理由需要重新论证");
    }
}
