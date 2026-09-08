package interview.homegrown.modules.drill.web.dto;

import java.time.Instant;
import java.util.List;

/** 个人知识资料的轻量视图，不向前端暴露解析后的全文。 */
public record CorpusView(Long id, String name, int charCount, String sourceType, Instant createdAt,
                         String overview, String indexState, List<String> topics, int chunkCount,
                         boolean hasOriginal) {
}
