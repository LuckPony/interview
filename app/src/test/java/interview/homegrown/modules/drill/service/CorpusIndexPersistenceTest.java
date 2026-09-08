package interview.homegrown.modules.drill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.homegrown.common.ai.AiSettingsService;
import interview.homegrown.common.ai.StructuredOutputInvoker;
import interview.homegrown.modules.drill.domain.Corpus;
import interview.homegrown.modules.drill.domain.CorpusChunk;
import interview.homegrown.modules.drill.repository.CorpusRepository;
import interview.homegrown.modules.drill.repository.CorpusChunkRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.assertj.core.api.Assertions.assertThat;

class CorpusIndexPersistenceTest {
  @Test @DisplayName("索引同时持久化资料简介与章节，模型调用不在数据库事务内")
  void persistsOverview() {
    var corpora = mock(CorpusRepository.class); var chunks = mock(CorpusChunkRepository.class);
    var invoker = mock(StructuredOutputInvoker.class); var tx = mock(TransactionTemplate.class);
    boolean[] inTransaction = {false};
    doAnswer(call -> {
      inTransaction[0] = true;
      try { call.<Consumer<TransactionStatus>>getArgument(0).accept(mock(TransactionStatus.class)); }
      finally { inTransaction[0] = false; }
      return null;
    }).when(tx).executeWithoutResult(any());
    Corpus c = new Corpus(); c.setId(1L); c.setText("# 线程池\n\n核心线程和工作队列"); c.setName("笔记.md");
    when(corpora.findById(1L)).thenReturn(Optional.of(c)); when(corpora.findLockedById(1L)).thenReturn(Optional.of(c));
    when(invoker.invoke(anyString(), anyString(), eq(CorpusIndexer.IndexOutput.class))).thenAnswer(call -> {
      assertThat(inTransaction[0]).isFalse();
      return new CorpusIndexer.IndexOutput("理解线程池机制", List.of(new CorpusIndexer.IndexOutput.ChunkMeta(0, "线程池机制", "线程池", "任务提交过程")));
    });
    doAnswer(call -> {
      List<CorpusChunk> saved = call.getArgument(0);
      assertThat(saved).hasSize(1); assertThat(saved.getFirst().getText()).contains("核心线程和工作队列");
      assertThat(saved.getFirst().getTopic()).isEqualTo("线程池"); assertThat(inTransaction[0]).isTrue(); return saved;
    }).when(chunks).saveAll(any());
    var indexer = new CorpusIndexer(corpora, chunks, invoker, new ObjectMapper(), mock(AiSettingsService.class), tx);
    try { indexer.index(1L); } finally { indexer.shutdown(); }
    assertThat(c.getOverview()).isEqualTo("理解线程池机制"); assertThat(c.getIndexState()).isEqualTo("READY");
  }
}
