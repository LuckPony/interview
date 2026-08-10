package interview.homegrown.modules.drill.service;

import java.util.List;

/**
 * 文本相似度闸门。抽象成接口是为了将来能换成 embedding 实现（pgvector 已在依赖里），
 * 但默认实现刻意用<b>纯确定性算法</b>：不依赖外部服务、不花 token、结果可复现、离线可测。
 *
 * <p>用途有二：
 * <ol>
 *   <li>出题去重的兜底硬闸（新题干 vs 该概念历史题干）；</li>
 *   <li>内化笔记的抄写检测（用户笔记 vs 题干与评分点）。</li>
 * </ol>
 */
public interface SimilarityGuard {

    /**
     * 对称相似度（Jaccard）：0.0（完全不同）~ 1.0（完全相同）。
     * 适合"两条题干是不是一回事"这种<b>等价判断</b>。
     */
    double similarity(String a, String b);

    /**
     * 非对称包含度：candidate 里有多大比例的片段来自 source，即 |A∩B| / |A|。
     *
     * <p>为什么抄写检测不能用 Jaccard：用户笔记 200 字，题干 500 字，哪怕笔记<b>整段照抄</b>，
     * 分母被 union 撑大后 Jaccard 也只有 0.3 左右，阈值根本没法定。而包含度算的是
     * "你写的东西里有多少是别人的"，整段照抄必然逼近 1.0，与 source 的长度无关。
     * 检测抄袭要问的是这个问题，不是"你俩像不像"。
     */
    double containment(String candidate, String source);

    /** 与候选集中任意一条的相似度是否超过阈值 */
    default boolean tooSimilar(String candidate, List<String> existing, double threshold) {
        return maxSimilarity(candidate, existing) > threshold;
    }

    default double maxSimilarity(String candidate, List<String> existing) {
        double max = 0.0;
        for (String s : existing) {
            max = Math.max(max, similarity(candidate, s));
        }
        return max;
    }

    /**
     * 对整个来源集合求包含度。注意是<b>先合并再算</b>，不是逐条取最大：
     * 抄写者常把题干抄一句、评分点抄一句拼起来，逐条算每条都不超阈值，合并算就现原形。
     */
    default double containmentOfAll(String candidate, List<String> sources) {
        return containment(candidate, String.join("\n", sources));
    }
}
