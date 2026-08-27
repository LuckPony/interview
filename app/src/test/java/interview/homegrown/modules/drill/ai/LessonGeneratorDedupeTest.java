package interview.homegrown.modules.drill.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LessonGeneratorDedupeTest {

    @Test
    @DisplayName("完全重复与仅空格差异的子知识点只保留一个")
    void exactDuplicates() {
        List<String> out = LessonGenerator.dedupeSimilar(
                List.of("列表推导式", "列表推导式", "列表推导式 ", "List 推导式"));
        assertThat(out).containsExactly("列表推导式", "List 推导式");
    }

    @Test
    @DisplayName("近似重复（说法不同、内容几乎一样）只保留第一个")
    void nearDuplicates() {
        List<String> out = LessonGenerator.dedupeSimilar(List.of(
                "列表推导式", "列表推导式的用法", "列表推导式使用技巧"));
        assertThat(out).containsExactly("列表推导式");
    }

    @Test
    @DisplayName("短词包含在长词中（≥4字符）视为重复")
    void containment() {
        List<String> out = LessonGenerator.dedupeSimilar(List.of(
                "异常处理机制", "异常处理"));
        // 「异常处理」被「异常处理机制」包含 → 视为重复，保留第一个
        assertThat(out).containsExactly("异常处理机制");
    }

    @Test
    @DisplayName("粒度不同的合法子点不被误删（排序 vs 堆排序）")
    void keepsLegitGranularity() {
        List<String> out = LessonGenerator.dedupeSimilar(List.of(
                "排序算法", "堆排序", "快速排序"));
        // 「排序算法」长度 4 且不包含于「堆排序」/「快速排序」；彼此不是包含关系 → 全部保留
        assertThat(out).containsExactly("排序算法", "堆排序", "快速排序");
    }

    @Test
    @DisplayName("空项被过滤")
    void empties() {
        List<String> out = LessonGenerator.dedupeSimilar(List.of("", "  ", "多态", "多态与接口"));
        assertThat(out).containsExactly("多态", "多态与接口");
    }
}
