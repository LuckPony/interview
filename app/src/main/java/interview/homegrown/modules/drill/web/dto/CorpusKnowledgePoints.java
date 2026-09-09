package interview.homegrown.modules.drill.web.dto;

import java.util.List;

public record CorpusKnowledgePoints(boolean indexed, List<Point> points) {
  public record Point(String name, int chunkCount, List<String> snippets) {}
}
