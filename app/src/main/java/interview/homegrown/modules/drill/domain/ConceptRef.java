package interview.homegrown.modules.drill.domain;

/** 一道题里参与的单个概念及其角色。 */
public record ConceptRef(Long conceptId, String topic, int layer, String name,
                         String description, ConceptRole role) {

    public static ConceptRef of(Concept c, ConceptRole role) {
        return new ConceptRef(c.getId(), c.getTopic(), c.getLayer(), c.getName(), c.getDescription(), role);
    }
}
