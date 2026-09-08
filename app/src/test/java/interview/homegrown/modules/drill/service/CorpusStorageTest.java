package interview.homegrown.modules.drill.service;

import interview.homegrown.common.ai.AiSettingsService;
import interview.homegrown.common.exception.BusinessException;
import interview.homegrown.infrastructure.file.DocumentParseService;
import interview.homegrown.infrastructure.file.FileStorageService;
import interview.homegrown.infrastructure.file.TextCleaningService;
import interview.homegrown.modules.drill.ai.FileParser;
import interview.homegrown.modules.drill.domain.Corpus;
import interview.homegrown.modules.drill.repository.ConceptRepository;
import interview.homegrown.modules.drill.repository.CorpusRepository;
import interview.homegrown.modules.drill.repository.StudyPlanRepository;
import interview.homegrown.modules.interview.repository.InterviewSessionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorpusStorageTest {
  @TempDir Path directory;
  final CorpusRepository repo = mock(CorpusRepository.class);
  final InterviewSessionRepository interviews = mock(InterviewSessionRepository.class);
  CorpusService service(FileStorageService files) {
    return new CorpusService(repo, mock(StudyPlanRepository.class), mock(ConceptRepository.class), mock(FileParser.class), mock(CorpusIndexer.class),
        mock(CorpusLibraryService.class), interviews, files, new DocumentParseService(new TextCleaningService()), mock(AiSettingsService.class));
  }
  @Test @DisplayName("上传保留原始文件，删除仅在事务提交后清理，拒绝任意路径读取")
  void originalLifecycle() throws Exception {
    FileStorageService files = new FileStorageService(directory.toString());
    when(repo.save(any(Corpus.class))).thenAnswer(call -> { Corpus c = call.getArgument(0); c.setId(1L); return c; });
    byte[] bytes = "# 线程池\n\n任务队列与拒绝策略".getBytes(StandardCharsets.UTF_8);
    Corpus c = service(files).upload(new MockMultipartFile("file", "notes.md", "text/markdown", bytes), 7L);
    assertThat(files.read(c.getOriginalKey()).getContentAsByteArray()).isEqualTo(bytes);
    when(repo.findLockedById(1L)).thenReturn(Optional.of(c));
    TransactionSynchronizationManager.initSynchronization();
    try {
      service(files).delete(1L, 7L);
      assertThat(files.read(c.getOriginalKey()).exists()).isTrue();
      TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());
      assertThatThrownBy(() -> files.read(c.getOriginalKey())).isInstanceOf(BusinessException.class);
    } finally { TransactionSynchronizationManager.clearSynchronization(); }
    assertThatThrownBy(() -> files.read("../outside.txt")).isInstanceOf(BusinessException.class);
  }
  @Test @DisplayName("已有面试引用的资料不能删除")
  void interviewProtectsDocument() {
    Corpus c = new Corpus(); c.setId(1L); c.setUserId(7L);
    when(repo.findLockedById(1L)).thenReturn(Optional.of(c));
    when(interviews.existsByUserIdAndCorpusId(7L, 1L)).thenReturn(true);
    assertThatThrownBy(() -> service(new FileStorageService(directory.toString())).delete(1L, 7L)).isInstanceOf(BusinessException.class).hasMessageContaining("引用");
  }
}
