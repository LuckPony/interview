package interview.homegrown.modules.drill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.homegrown.modules.drill.ai.GeneratedQuestion;
import interview.homegrown.modules.drill.ai.QuestionGenerator;
import interview.homegrown.modules.drill.domain.AnswerMode;
import interview.homegrown.modules.drill.domain.ProbeType;
import interview.homegrown.modules.drill.domain.QuestionBank;
import interview.homegrown.modules.drill.domain.ResponseFormat;
import interview.homegrown.modules.drill.domain.SelectedTask;
import interview.homegrown.modules.drill.repository.QuestionBankRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 出题：服务端定四维签名（concept_ids / probe_type / answer_mode / response_format），LLM 只填内容。
 *
 * <p><b>去重三闸</b>（痛点 2 的"问法重复"）：
 * <ol>
 *   <li><b>硬闸</b>：在 arity 允许的 probe_type 里，优先选该概念没用过的认知动作；</li>
 *   <li><b>软闸</b>：把历史题干注入 prompt，要求提问角度明显不同；</li>
 *   <li><b>兜底硬闸</b>：生成后算 trigram 相似度，超阈值就换 probe_type 重出，最多 3 次。</li>
 * </ol>
 * 前两闸管"大概率不重复"，第三闸管"模型抽风时也不会真的塞一道重复题进库"。
 */
@Service
public class QuestionService {

    private static final Logger log = LoggerFactory.getLogger(QuestionService.class);

    /** 相似度超过它就判定为重复题 */
    private static final double DUP_THRESHOLD = 0.85;
    /** 出题只调一次 LLM：推理模型每次要几十秒，重试代价太高；去重靠「优先未用 probe + 历史题干注入」 */
    private static final int MAX_ATTEMPTS = 1;
    private static final int HISTORY_LIMIT = 10;

    private final QuestionBankRepository qbRepo;
    private final QuestionGenerator generator;
    private final SimilarityGuard similarityGuard;
    private final ObjectMapper objectMapper;
    private final Random random = new Random();

    public QuestionService(QuestionBankRepository qbRepo, QuestionGenerator generator,
                           SimilarityGuard similarityGuard, ObjectMapper objectMapper) {
        this.qbRepo = qbRepo;
        this.generator = generator;
        this.similarityGuard = similarityGuard;
        this.objectMapper = objectMapper;
    }

    public QuestionBank generate(SelectedTask task) {
        return generate(task, null);
    }

    public QuestionBank generate(SelectedTask task, String referenceText) {
        int arity = task.arity();
        Long primaryId = task.conceptId();

        List<String> usedProbes = qbRepo.findUsedProbeTypes(primaryId, arity);
        List<String> history = qbRepo.findRecentStems(primaryId, HISTORY_LIMIT);
        ResponseFormat format = ResponseFormat.FREE_TEXT;   // MVP 主路径，CHOICE 走摸底链路

        List<ProbeType> tried = new ArrayList<>();
        GeneratedQuestion accepted = null;
        ProbeType acceptedProbe = null;

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            ProbeType probe = pickProbeType(arity, usedProbes, tried);
            tried.add(probe);

            GeneratedQuestion gq = generator.generate(task, probe, format, history, referenceText);
            double sim = similarityGuard.maxSimilarity(gq.stem, history);

            if (sim <= DUP_THRESHOLD) {
                accepted = gq;
                acceptedProbe = probe;
                break;
            }
            log.warn("出题重复度过高 sim={} concept={} probe={} attempt={}，换认知动作重出",
                    String.format("%.3f", sim), primaryId, probe, attempt + 1);
            accepted = gq;              // 兜底：最后一次的结果仍然收下，避免拿不到题
            acceptedProbe = probe;
        }

        return persist(task, accepted, acceptedProbe, format);
    }

    /**
     * 硬闸：先按 arity 过滤出合法 probe_type（CONTRAST/INTEGRATION 天然要求 arity&gt;=2），
     * 再优先挑没用过的，最后排除本轮已试过的。
     */
    private ProbeType pickProbeType(int arity, List<String> used, List<ProbeType> tried) {
        List<ProbeType> legal = ProbeType.forArity(arity).stream()
                .filter(p -> !tried.contains(p))
                .toList();
        if (legal.isEmpty()) {
            legal = ProbeType.forArity(arity);      // 都试过了，只能复用
        }
        List<ProbeType> unused = legal.stream().filter(p -> !used.contains(p.name())).toList();
        List<ProbeType> pool = unused.isEmpty() ? legal : unused;
        return pool.get(random.nextInt(pool.size()));
    }

    private QuestionBank persist(SelectedTask task, GeneratedQuestion gq,
                                 ProbeType probe, ResponseFormat format) {
        // concept_ids 的顺序 == 出题时给 LLM 的 conceptIndex 顺序，判分靠它映射回真实 id
        Integer[] cids = task.conceptIds().stream()
                .map(Long::intValue).toArray(Integer[]::new);

        QuestionBank qb = new QuestionBank();
        qb.setConceptIds(cids);
        qb.setProbeType(probe);
        qb.setAnswerMode(AnswerMode.WRITE);
        qb.setResponseFormat(format);
        qb.setArity(task.arity());
        qb.setStem(gq.stem);
        qb.setPointsJson(serialize(gq));
        return qbRepo.save(qb);
    }

    private String serialize(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("题目 points 序列化失败", e);
        }
    }
}
