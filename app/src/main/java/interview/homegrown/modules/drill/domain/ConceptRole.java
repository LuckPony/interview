package interview.homegrown.modules.drill.domain;

/**
 * 概念在一道题里的角色。
 * PRIMARY：本题真正要推进的目标概念，掌握度按正常规则升降。
 * ANCHOR：已掌握的挂靠点，只是用来把新知识挂上去。
 * 关键规则：ANCHOR 的 grade 封顶 GOOD（不给 EASY，避免蹭分虚高），
 * 但不封底（答错照样掉，因为那是真的忘了）。
 */
public enum ConceptRole {
    PRIMARY,
    ANCHOR
}
