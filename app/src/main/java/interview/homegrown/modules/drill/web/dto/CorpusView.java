package interview.homegrown.modules.drill.web.dto;

/** POST /api/corpus/upload 的返回。 */
public record CorpusView(Long id, String name, int charCount) {
}
