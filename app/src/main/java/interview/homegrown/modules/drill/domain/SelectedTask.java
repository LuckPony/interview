package interview.homegrown.modules.drill.domain;

import java.util.List;

/**
 * 选题闸门产出的确定性结果，传给出题器。不含任何 LLM 决策。
 * concepts[0] 恒为 PRIMARY，其余为 ANCHOR。arity = concepts.size()。
 */
public record SelectedTask(List<ConceptRef> concepts) {

    public static SelectedTask single(ConceptRef primary) {
        return new SelectedTask(List.of(primary));
    }

    public ConceptRef primary() {
        return concepts.get(0);
    }

    public int arity() {
        return concepts.size();
    }

    public Long conceptId() {
        return primary().conceptId();
    }

    public List<Long> conceptIds() {
        return concepts.stream().map(ConceptRef::conceptId).toList();
    }
}
