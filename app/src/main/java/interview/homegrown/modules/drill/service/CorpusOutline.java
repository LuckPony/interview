package interview.homegrown.modules.drill.service;

import interview.homegrown.modules.drill.domain.CorpusChunk;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** 统一整理目录、候选知识点和生成上下文；只做逻辑归组，不删除原文或已有块 ID。 */
public final class CorpusOutline {
  private CorpusOutline() {}
  private static final Pattern PREFIX = Pattern.compile("^(?:#{1,6}\\s+|第[一二三四五六七八九十百千零0-9]+[章节篇卷部](?:分)?[\\s、：:.]*|[0-9]+(?:\\.[0-9]+)*(?:[.．、):：]\\s*|\\s+)|[IVXLCDM]{1,6}\\.\\s+)");
  private static final Pattern ACADEMIC = Pattern.compile("(?i)^(abstract|introduction|background|methods?|materials and methods|results(?: and discussion)?|discussion|conclusions?|references|acknowledg[e]?ments|摘要|引言|结论|参考文献|致谢)$");

  public static String label(String raw) {
    if (raw == null) return "";
    String text = raw.replace('\u00a0', ' ').replaceAll("[\\p{Cntrl}\\p{Cf}]", " ").strip();
    text = PREFIX.matcher(text).replaceFirst("");
    text = PREFIX.matcher(text).replaceFirst("").replaceAll("\\s+", " ").replaceAll("\\s+#+$", "").strip();
    long letters = text.codePoints().filter(c -> c < 128 && Character.isLetter(c)).count();
    boolean chinese = text.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN);
    if (!chinese && letters < 2 && !text.equals("C++") && !text.equals("C#")) return "";
    if (text.length() > 120 || text.matches("(?:片段|正文片段|内容片段)\\s*[0-9]+")
        || text.matches(".*[=∑∫<>\\[\\]{}].*") || text.matches("(?i)^(https?://|doi:|arxiv:).*")
        || text.matches(".*\\.{3,}\\s*[0-9]+$")) return "";
    // 全大写英文论文标题按句式显示，缩写（MRI、API、HTTP）保留。
    if (text.matches("[A-Z][A-Z -]{4,}")) text = text.substring(0, 1) + text.substring(1).toLowerCase(Locale.ROOT);
    return text;
  }

  public static boolean heading(String line) {
    String text = line.strip();
    if (label(text).isBlank() || text.length() > 120 || text.matches(".*[。；;!?！？]$")) return false;
    return PREFIX.matcher(text).find() || ACADEMIC.matcher(text).matches();
  }

  public static String key(String label) { return label.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}]+", ""); }

  public static List<String> labels(List<String> raw) {
    var unique = new LinkedHashMap<String, String>();
    for (String value : raw) {
      String label = label(value);
      if (!label.isBlank()) unique.putIfAbsent(key(label), label);
    }
    return List.copyOf(unique.values());
  }

  public static List<Section> sections(List<CorpusChunk> chunks) {
    List<Section> result = new ArrayList<>();
    for (CorpusChunk chunk : chunks) {
      String title = label(chunk.getTitle());
      String topic = label(chunk.getTopic());
      if (title.isBlank()) title = topic;
      Section previous = result.isEmpty() ? null : result.getLast();
      // 页码、公式、超长无标题续片并入前一节；开篇无标题内容保留为“正文导读”。
      if (previous != null && (title.isBlank() || key(title).equals(key(previous.title())))) {
        previous.chunks().add(chunk);
      } else {
        result.add(new Section(title.isBlank() ? "正文导读" : title, topic, new ArrayList<>(List.of(chunk))));
      }
    }
    return result;
  }

  public record Section(String title, String topic, List<CorpusChunk> chunks) {
    public Long id() { return chunks.getFirst().getId(); }
    public String name() { return topic.isBlank() ? title : topic; }
    public String text() { return String.join("\n\n", chunks.stream().map(CorpusChunk::getText).toList()); }
    public int charCount() { return text().length(); }
    public String summary() {
      var summaries = new LinkedHashMap<String, String>();
      for (CorpusChunk chunk : chunks) {
        String value = chunk.getSummary();
        if (value == null || value.isBlank()) continue;
        value = value.replaceAll("\\s+", " ").strip();
        if (label(value).isBlank() || key(value).equals(key(title)) || key(value).equals(key(name()))
            || key(value).equals(key(chunk.getTitle() == null ? "" : chunk.getTitle()))) continue;
        summaries.putIfAbsent(key(value), value);
      }
      String value = String.join("；", summaries.values());
      return value.length() > 160 ? value.substring(0, 160) + "…" : value;
    }
    public boolean referenceOnly() { return title.matches("(?i)^(references|bibliography|acknowledg[e]?ments|参考文献|致谢)$"); }
  }
}
