package interview.homegrown.modules.drill.service;

import interview.homegrown.common.exception.BusinessException;
import interview.homegrown.infrastructure.file.FileStorageService;
import interview.homegrown.infrastructure.redis.RedisService;
import interview.homegrown.modules.drill.domain.Corpus;
import interview.homegrown.modules.drill.domain.CorpusChunk;
import interview.homegrown.modules.drill.domain.StudyPlan;
import interview.homegrown.modules.drill.repository.CorpusRepository;
import interview.homegrown.modules.drill.repository.CorpusChunkRepository;
import interview.homegrown.modules.drill.repository.StudyPlanRepository;
import interview.homegrown.modules.interview.model.InterviewSessionEntity;
import interview.homegrown.modules.interview.model.InterviewStatus;
import interview.homegrown.modules.interview.repository.InterviewSessionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class CorpusLibraryServiceTest {
  final CorpusRepository corpora = mock(CorpusRepository.class);
  final CorpusChunkRepository chunks = mock(CorpusChunkRepository.class);
  final StudyPlanRepository plans = mock(StudyPlanRepository.class);
  final InterviewSessionRepository interviews = mock(InterviewSessionRepository.class);
  final RedisService redis = mock(RedisService.class);
  final CorpusLibraryService service = new CorpusLibraryService(corpora, chunks, plans, interviews, mock(FileStorageService.class), redis);

  Corpus document() {
    Corpus c = new Corpus(); c.setId(1L); c.setUserId(7L); c.setName("并发学习.md");
    c.setText("第一章" + "x".repeat(30000) + "最后一章"); c.setCharCount(c.getText().length());
    when(corpora.findById(1L)).thenReturn(Optional.of(c));
    return c;
  }
  CorpusChunk section(long id, int seq, String topic) {
    CorpusChunk c = new CorpusChunk(); c.setId(id); c.setCorpusId(1L); c.setSeq(seq);
    c.setTitle(topic); c.setTopic(topic); c.setText(topic + "x".repeat(20000)); c.setCharCount(c.getText().length()); return c;
  }

  @Test @DisplayName("资料详情和生成上下文拒绝跨用户访问")
  void ownership() {
    document();
    assertThatThrownBy(() -> service.detail(1L, 8L)).isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.reference(1L, null)).isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.originalTicket(1L, 8L)).isInstanceOf(BusinessException.class);
  }

  @Test @DisplayName("索引生成依据覆盖首尾章节且限制上下文长度")
  void indexedReference() {
    document(); when(chunks.findByCorpusIdOrderBySeqAsc(1L)).thenReturn(List.of(section(1, 0, "线程基础"), section(2, 1, "内存模型"), section(3, 2, "故障排查")));
    String ref = service.reference(1L, 7L);
    assertThat(ref).contains("[来源 S1] 线程基础", "[来源 S3] 故障排查").hasSizeLessThan(20000);
  }

  @Test @DisplayName("旧资料无索引时从原文首中尾抽样而非只取开头")
  void legacyReference() {
    document();
    assertThat(service.reference(1L, 7L)).contains("第一章", "最后一章", "原文抽样 2").hasSizeLessThan(19000);
  }

  @Test @DisplayName("工具库只允许读取当前资料的章节且明确截断范围")
  void toolText() {
    document(); var part = section(3, 0, "知识"); part.setCorpusId(2L);
    when(chunks.findById(3L)).thenReturn(Optional.of(part));
    assertThatThrownBy(() -> service.text(1L, 7L, 3L)).isInstanceOf(BusinessException.class);
    var text = service.text(1L, 7L, null);
    assertThat(text.text()).hasSize(12000); assertThat(text.truncated()).isTrue(); assertThat(text.totalChars()).isGreaterThan(12000);
  }

  @Test @DisplayName("原文链接只签发五分钟资料凭证，过期后不可读取")
  void tickets() {
    document().setOriginalKey("saved.pdf"); String path = service.originalTicket(1L, 7L);
    assertThat(path).matches("/api/corpus/original/[a-f0-9]{32}");
    String token = path.substring(path.lastIndexOf('/') + 1);
    verify(redis).set("corpus:preview:" + token, "7:1", Duration.ofMinutes(5));
    when(redis.get("corpus:preview:" + token)).thenReturn(Optional.of("7:1"));
    assertThat(service.fromTicket(token).getId()).isEqualTo(1L);
    when(redis.get("corpus:preview:" + token)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.fromTicket(token)).isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.fromTicket("../other")).isInstanceOf(BusinessException.class);
  }

  @Test @DisplayName("没有原件的历史资料不能签发原件链接，但可单独查看解析文本")
  void legacyTextIsNotOriginal() {
    document();
    assertThatThrownBy(() -> service.originalTicket(1L, 7L)).isInstanceOf(BusinessException.class).hasMessageContaining("未保存原文件");
    assertThat(service.textTicket(1L, 7L)).matches("/api/corpus/parsed/[a-f0-9]{32}");
    assertThatThrownBy(() -> service.textTicket(1L, 8L)).isInstanceOf(BusinessException.class);
  }

  @Test @DisplayName("知识库、建计划和生成上下文使用一致的目录，历史碎片链接可读取完整章节")
  void unifiedOutline() {
    document();
    var main = CorpusOutlineTest.chunk(1, "2. DATA AND PREPROCESSING", "数据预处理");
    var formula = CorpusOutlineTest.chunk(2, "3 (λ", "公式与续页");
    var methods = CorpusOutlineTest.chunk(3, "3. METHODS", "研究方法");
    var references = CorpusOutlineTest.chunk(4, "4. REFERENCES", "论文引用");
    when(chunks.findByCorpusIdOrderBySeqAsc(1L)).thenReturn(List.of(main, formula, methods, references));
    assertThat(service.detail(1L, 7L).sections()).extracting(s -> s.title()).containsExactly("Data and preprocessing", "Methods", "References");
    assertThat(service.knowledgePoints(1L, 7L).points()).extracting(p -> p.name()).containsExactly("Data and preprocessing", "Methods");
    assertThat(service.knowledgePoints(1L, 7L).points().getFirst().chunkCount()).isEqualTo(2);
    assertThat(service.text(1L, 7L, 2L).text()).isEqualTo("数据预处理\n\n公式与续页");
    assertThat(service.reference(1L, 7L)).contains("[来源 S1] Data and preprocessing", "公式与续页", "[来源 S2] Methods");
    assertThatThrownBy(() -> service.knowledgePoints(1L, 8L)).isInstanceOf(BusinessException.class);
  }

  @Test @DisplayName("资料使用关系同时包含直接引用面试与经学习计划引用的面试")
  void usages() {
    document(); StudyPlan plan = new StudyPlan(); plan.setId(20L); plan.setCorpusId(1L); plan.setTitle("并发路线");
    when(plans.findByUserId(7L)).thenReturn(List.of(plan));
    InterviewSessionEntity direct = new InterviewSessionEntity(); direct.setId("a"); direct.setCorpusId(1L); direct.setStatus(InterviewStatus.IN_PROGRESS);
    InterviewSessionEntity indirect = new InterviewSessionEntity(); indirect.setId("b"); indirect.setPlanIds("20"); indirect.setStatus(InterviewStatus.COMPLETED);
    when(interviews.findByUserIdOrderByCreatedAtDesc(7L)).thenReturn(List.of(direct, indirect));
    assertThat(service.detail(1L, 7L).usages()).extracting(u -> u.kind()).containsExactly("PLAN", "INTERVIEW", "INTERVIEW_PLAN");
  }
}
