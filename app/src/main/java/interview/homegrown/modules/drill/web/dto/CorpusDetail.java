package interview.homegrown.modules.drill.web.dto;

import java.util.List;

public record CorpusDetail(CorpusView document, List<Section> sections, List<Usage> usages) {
  public record Section(Long id, int sequence, String title, String topic, String summary, int charCount) {}
  public record Usage(String kind, String id, String title, String status) {}
}
