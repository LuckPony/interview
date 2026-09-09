package interview.homegrown.modules.drill.service;

import interview.homegrown.modules.drill.domain.CorpusChunk;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CorpusOutlineTest {
  static CorpusChunk chunk(long id, String title, String text) {
    CorpusChunk c = new CorpusChunk(); c.setId(id); c.setSeq((int) id - 1); c.setTitle(title);
    c.setText(text); c.setCharCount(text.length()); c.setCorpusId(1L); return c;
  }

  @Test @DisplayName("论文标题清除重复编号，页码和公式不是标题，技术缩写保留")
  void labels() {
    assertThat(CorpusOutline.label("## 2. DATA AND PREPROCESSING")).isEqualTo("Data and preprocessing");
    assertThat(CorpusOutline.label("3 (λ")).isEmpty();
    assertThat(CorpusOutline.label("2")).isEmpty();
    assertThat(CorpusOutline.label("\u200b")).isEmpty();
    assertThat(CorpusOutline.label("MRI")).isEqualTo("MRI");
    assertThat(CorpusOutline.label("C++")).isEqualTo("C++");
    assertThat(CorpusOutline.labels(List.of("1. INTRODUCTION", "Introduction", "2", "## 方法"))).containsExactly("Introduction", "方法");
  }

  @Test @DisplayName("历史噪声块归入上一节，去掉重复摘要，不删除块 ID 或原文")
  void legacyOutline() {
    var intro = chunk(1, "", "作者、摘要和开篇内容");
    var section = chunk(2, "2. DATA AND PREPROCESSING", "数据内容"); section.setSummary(section.getTitle());
    var formula = chunk(3, "3 (λ", "3 (λ\n+ 4)");
    var page = chunk(4, "2", "续页内容");
    var methods = chunk(5, "3. METHODS", "方法内容");
    var result = CorpusOutline.sections(List.of(intro, section, formula, page, methods));
    assertThat(result).extracting(CorpusOutline.Section::title).containsExactly("正文导读", "Data and preprocessing", "Methods");
    assertThat(result.get(1).summary()).isEmpty();
    assertThat(result.get(1).chunks()).extracting(CorpusChunk::getId).containsExactly(2L, 3L, 4L);
    assertThat(result.get(1).text()).contains("数据内容", "3 (λ\n+ 4)", "续页内容");
    assertThat(formula.getTitle()).isEqualTo("3 (λ");
  }

  @Test @DisplayName("新切块不把公式和代码围栏中的标题当章节，后文不丢失")
  void splitAvoidsNoise() {
    String text = "1. INTRODUCTION\n正文内容\n3 (λ\n2\n```java\n2. FAKE HEADING\n```\n3. METHODS\n最终的方法";
    var result = CorpusIndexer.split(text);
    assertThat(result).hasSize(2);
    assertThat(result.getFirst().text()).contains("3 (λ", "FAKE HEADING");
    assertThat(result.getLast().text()).contains("最终的方法");
  }
}
