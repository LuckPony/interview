package interview.homegrown.common.util;

/** 文本截断工具：统一各处私有 truncate 实现。 */
public final class TextUtil {

    private TextUtil() {
    }

    /** 超长文本截断（保留开头），追加省略号。null/短文本原样返回。 */
    public static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max) + "…";
    }

    /** 代码感知截断：含 ``` 围栏的文本不截断（保证代码块完整性）；其余按 max 截断。 */
    public static String truncateCodeAware(String s, int max) {
        if (s == null || s.length() <= max) return s == null ? "" : s;
        if (s.contains("```")) return s;
        return s.substring(0, max) + "…";
    }
}
