package interview.homegrown.modules.drill.grader;

import interview.homegrown.modules.drill.domain.ConceptRole;
import interview.homegrown.modules.drill.domain.Grade;

import java.math.BigDecimal;

/**
 * 组合题里单个概念的子分。掌握度是 per concept 更新的，所以判分必须能拆到概念粒度，
 * 否则一道 arity=3 的题会把三个概念一起拔高或一起打死，画像就糊了。
 */
public record ConceptScore(Long conceptId, ConceptRole role, BigDecimal score, Grade grade) {
}
