package interview.homegrown.modules.drill.service;

import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * 字符级 trigram Jaccard 相似度。
 *
 * <p>选它而不是 embedding 的三个理由：
 * <ol>
 *   <li>中英文混排的技术题干里，抄写/换皮的痕迹主要体现在<b>字面重叠</b>，trigram 抓得很准；</li>
 *   <li>零网络调用，出题重试链路不会因为 embedding 服务抖动而卡死；</li>
 *   <li>确定性可复现，符合"能算清楚的不交给模型"的主张。</li>
 * </ol>
 *
 * <p>局限要认：它抓不住"同义改写"（换词不换意）。将来若要补，实现另一个
 * {@link SimilarityGuard} 走 embedding 即可，调用方无需改动。
 */
@Component
public class NgramSimilarityGuard implements SimilarityGuard {

    private static final int N = 3;

    @Override
    public double similarity(String a, String b) {
        Set<String> ga = trigrams(normalize(a));
        Set<String> gb = trigrams(normalize(b));
        if (ga.isEmpty() || gb.isEmpty()) {
            return 0.0;
        }
        Set<String> inter = new HashSet<>(ga);
        inter.retainAll(gb);
        int unionSize = ga.size() + gb.size() - inter.size();
        return unionSize == 0 ? 0.0 : (double) inter.size() / unionSize;
    }

    @Override
    public double containment(String candidate, String source) {
        Set<String> gc = trigrams(normalize(candidate));
        Set<String> gs = trigrams(normalize(source));
        if (gc.isEmpty() || gs.isEmpty()) {
            return 0.0;
        }
        Set<String> inter = new HashSet<>(gc);
        inter.retainAll(gs);
        return (double) inter.size() / gc.size();   // 分母只用 candidate，与 source 长度无关
    }

    /** 去掉空白与标点、统一小写，避免"换个标点就当新题" */
    private String normalize(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toLowerCase().toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private Set<String> trigrams(String s) {
        Set<String> out = new HashSet<>();
        if (s.length() < N) {
            if (!s.isEmpty()) out.add(s);
            return out;
        }
        for (int i = 0; i + N <= s.length(); i++) {
            out.add(s.substring(i, i + N));
        }
        return out;
    }
}
