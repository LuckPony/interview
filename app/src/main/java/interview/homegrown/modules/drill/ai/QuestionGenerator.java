package interview.homegrown.modules.drill.ai;

import interview.homegrown.common.ai.StructuredOutputInvoker;
import interview.homegrown.modules.drill.domain.ConceptRef;
import interview.homegrown.modules.drill.domain.ConceptRole;
import interview.homegrown.modules.drill.domain.ProbeType;
import interview.homegrown.modules.drill.domain.ResponseFormat;
import interview.homegrown.modules.drill.domain.SelectedTask;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.IntStream;

/**
 * 出题生成器：复用项目已有的 StructuredOutputInvoker（Spring AI ChatClient + 重试 + 结构化输出）。
 *
 * <p>服务端已经把四维签名（concept_ids / probe_type / answer_mode / response_format）和 arity 都定死了，
 * LLM 只负责把签名"填成一道人话题目"。它无权决定考哪个概念、几个概念、用什么认知动作。
 *
 * <p>历史题干以"避免雷同"的形式注入，这是去重三闸里的<b>软闸</b>；硬闸在 QuestionService。
 */
@Component
public class QuestionGenerator {

    private final StructuredOutputInvoker invoker;

    public QuestionGenerator(StructuredOutputInvoker invoker) {
        this.invoker = invoker;
    }

    public GeneratedQuestion generate(SelectedTask task, ProbeType probeType,
                                      ResponseFormat format, List<String> avoidStems,
                                      String referenceText) {
        String system = """
                你是一个严谨的面试备考出题器。只产出题目与评分点，严禁给出答案、解析或提示。
                评分点(points)必须是可客观核验的知识点，每条带权重 weight(1-3，越核心越大)。
                必须按概念分组输出 byConcept，conceptIndex 使用下面清单里给出的序号。
                不要使用中文破折号，输出严格遵循格式说明的 JSON。""";

        String conceptList = renderConcepts(task.concepts());
        String avoidBlock = avoidStems.isEmpty() ? "（暂无历史题目）"
                : String.join("\n", avoidStems.stream().map(s -> "- " + s).toList());

        String user = String.format("""
                本题涉及的概念清单（序号即 conceptIndex）：
                %s

                出题要求：
                - 认知动作类型：%s
                - 作答形态：%s
                - 概念数 arity：%d（必须恰好覆盖上面全部概念，不得增删）
                - 题干(stem)必须把这些概念真正咬合在一起，而不是拼接几个独立小问
                - PRIMARY 概念是本题真正要推进的目标，评分点应覆盖其核心，3-5 条
                - ANCHOR 概念是用户已掌握的挂靠点，评分点只考它与 PRIMARY 的关系或边界，1-3 条，
                  不要再考它的基础定义
                - byConcept 必须为每个 conceptIndex 各出一组评分点

                以下是该知识点已出过的题干，新题必须在提问角度上明显不同：
                %s
                """, conceptList, probeType, format, task.arity(), avoidBlock);

        // 资料注入：用户基于某本书/项目资料学习时，评分点须以资料为准，不得杜撰。
        if (referenceText != null && !referenceText.isBlank()) {
            user += "\n\n以下是本题概念对应的权威资料（来自用户上传的学习资料），评分点须以此为准，"
                    + "不得杜撰或超出资料范围：\n" + referenceText;
        }

        return invoker.invoke(system, user, GeneratedQuestion.class);
    }

    private String renderConcepts(List<ConceptRef> concepts) {
        return IntStream.range(0, concepts.size())
                .mapToObj(i -> {
                    ConceptRef c = concepts.get(i);
                    String roleHint = c.role() == ConceptRole.PRIMARY
                            ? "PRIMARY 目标概念" : "ANCHOR 已掌握锚点";
                    return String.format("[%d] %s（主题：%s，认知层 L%d，角色：%s）说明：%s",
                            i, c.name(), c.topic(), c.layer(), roleHint, c.description());
                })
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }
}
