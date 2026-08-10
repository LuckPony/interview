package interview.homegrown.modules.drill.grader;

import interview.homegrown.modules.drill.domain.Grade;
import interview.homegrown.modules.drill.domain.QuestionBank;

import java.math.BigDecimal;
import java.util.List;

/**
 * 判分策略接口。按 question_bank.response_format 分派到实现，编排层一个 if 都没有。
 * 所有实现输出统一的 GraderOutput：byConceptJson（审计留痕）+ 服务端算出的总分与档位
 * + per concept 子分（掌握度按概念粒度更新）。
 * MCQ / CODE 实现不走 LLM，保证判分客观可复现（痛点 3/5 的命门）。
 */
public interface Grader {

    GraderOutput grade(Long runId, QuestionBank question, String rawAnswer, boolean timed);

    record GraderOutput(String byConceptJson, BigDecimal rawScore, Grade grade,
                        List<ConceptScore> conceptScores) {
    }
}
