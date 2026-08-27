package interview.homegrown.modules.drill.ai;

import interview.homegrown.modules.drill.domain.ConceptLesson;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 讲解避重：siblingSummaries 应排除当前子点、提取摘要、忽略空讲解。 */
class LessonGeneratorSiblingSummaryTest {

    private final LessonGenerator gen = new LessonGenerator(null, null, null);

    private static ConceptLesson lesson(String sub, String text) {
        ConceptLesson c = new ConceptLesson();
        c.setConceptId(1L);
        c.setSubPoint(sub);
        c.setLessonText(text);
        c.setCharCount(text.length());
        return c;
    }

    @Test
    void excludesCurrentSubPoint() {
        var lessons = List.of(
                lesson("指针传递", "讲指针传递，重点是引用还是拷贝……"),
                lesson("计数器初始值", "计数器初始值是 0，Add 之后才会……"),
                lesson("Add与Done时序", "Done 先于 Add 会让计数器变负……"));
        var res = gen.siblingSummaries(lessons, "指针传递");

        assertThat(res).hasSize(2);
        assertThat(res).extracting(LessonGenerator.SiblingLesson::subPoint)
                .containsExactlyInAnyOrder("计数器初始值", "Add与Done时序");
        assertThat(res)
                .extracting(LessonGenerator.SiblingLesson::summary)
                .allSatisfy(s -> assertThat(s).isNotBlank());
    }

    @Test
    void truncatesLongSummaries() {
        String longText = "这是一个非常长的讲解，".repeat(60);
        var res = gen.siblingSummaries(List.of(lesson("A", longText)), "B");

        assertThat(res).hasSize(1);
        assertThat(res.get(0).summary()).hasSizeLessThanOrEqualTo(141); // 截断到 140 + "…"
    }

    @Test
    void ignoresBlankLessons() {
        var res = gen.siblingSummaries(List.of(lesson("A", "")), "B");

        assertThat(res).isEmpty();
    }

    @Test
    void returnsEmptyForNullOrEmptyInput() {
        assertThat(gen.siblingSummaries(null, "A")).isEmpty();
        assertThat(gen.siblingSummaries(List.of(), "A")).isEmpty();
    }
}
