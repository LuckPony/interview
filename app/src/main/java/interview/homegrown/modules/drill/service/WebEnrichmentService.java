package interview.homegrown.modules.drill.service;

import interview.homegrown.common.ai.LlmRawClient;
import interview.homegrown.modules.drill.domain.Concept;
import interview.homegrown.modules.drill.domain.WebContent;
import interview.homegrown.modules.drill.repository.ConceptRepository;
import interview.homegrown.modules.drill.repository.WebContentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 知识点互联网增强：建计划（confirm 落库）后，对每个知识点 web_search 预取一次
 * 「标准/权威内容」存 web_content，作为资料之外的补充素材随上下文注入。
 *
 * <p>默认开启、无开关（用户决策）。预取在 confirm 后<b>异步</b>执行，不阻塞建计划；
 * 单个知识点搜索失败只跳过该点（供应商不支持联网 / 超时都不致命）。
 * 内容截断到 {@link #MAX_WEB_CHARS}，防止超大搜索返回撑爆上下文。
 */
@Service
public class WebEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(WebEnrichmentService.class);

    /** 单条互联网内容注入上限（字符）。 */
    static final int MAX_WEB_CHARS = 8000;

    private final ConceptRepository conceptRepo;
    private final WebContentRepository webRepo;
    private final LlmRawClient rawClient;
    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "web-enrichment");
        t.setDaemon(true);
        return t;
    });

    public WebEnrichmentService(ConceptRepository conceptRepo, WebContentRepository webRepo,
                                LlmRawClient rawClient) {
        this.conceptRepo = conceptRepo;
        this.webRepo = webRepo;
        this.rawClient = rawClient;
    }

    /** 建计划落库后异步预取该方向全部知识点的互联网内容。幂等：已有内容跳过。 */
    public void enrichPlanAsync(Long planId) {
        if (planId == null) return;
        pool.submit(() -> {
            try {
                enrichPlan(planId);
            } catch (Exception e) {
                log.warn("知识点互联网预取失败 (planId={}): {}", planId, e.getMessage());
            }
        });
    }

    /** 同步预取：对每个知识点搜一次并存库。 */
    public synchronized void enrichPlan(Long planId) {
        List<Concept> concepts = conceptRepo.findByStudyPlanId(planId);
        for (Concept c : concepts) {
            if (webRepo.findByConceptId(c.getId()).isPresent()) continue;   // 幂等
            try {
                String query = "请搜索「" + c.getName() + "」在" + c.getTopic() + "方向的核心概念、"
                        + "原理与常见面试考点，并整理成一段要点说明（不要输出 Markdown 表格）。";
                String text = rawClient.webSearch(query);
                if (text == null || text.isBlank()) continue;
                WebContent wc = new WebContent();
                wc.setConceptId(c.getId());
                wc.setTitle(c.getName());
                wc.setText(truncate(text));
                wc.setCharCount(wc.getText().length());
                webRepo.save(wc);
            } catch (Exception e) {
                log.warn("知识点「{}」互联网预取失败，跳过: {}", c.getName(), e.getMessage());
            }
        }
    }

    private String truncate(String s) {
        if (s == null) return s;
        if (s.length() <= MAX_WEB_CHARS) return s;
        return s.substring(0, MAX_WEB_CHARS) + "\n…（互联网内容较长，已截断）";
    }
}
