package interview.homegrown.modules.drill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.homegrown.modules.drill.ai.GeneratedQuestion;
import interview.homegrown.modules.drill.ai.QuestionGenerator;
import interview.homegrown.modules.drill.domain.Concept;
import interview.homegrown.modules.drill.domain.ConceptRef;
import interview.homegrown.modules.drill.domain.ConceptRole;
import interview.homegrown.modules.drill.domain.DrillPhase;
import interview.homegrown.modules.drill.domain.DrillPurpose;
import interview.homegrown.modules.drill.domain.DrillRun;
import interview.homegrown.modules.drill.domain.DrillRunStatus;
import interview.homegrown.modules.drill.domain.Mastery;
import interview.homegrown.modules.drill.domain.ProbeType;
import interview.homegrown.modules.drill.domain.QuestionBank;
import interview.homegrown.modules.drill.domain.ResponseFormat;
import interview.homegrown.modules.drill.domain.SelectedTask;
import interview.homegrown.modules.drill.repository.ConceptRepository;
import interview.homegrown.modules.drill.repository.DrillRunRepository;
import interview.homegrown.modules.drill.repository.MasteryRepository;
import interview.homegrown.modules.drill.repository.QuestionBankRepository;
import interview.homegrown.modules.drill.web.dto.GradeView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * 三阶段练习的阶段3：迁移测试。
 *
 * <p>阶段1（独立作答）判分锁定基础档位（first_grade）后，若档位为 AGAIN/HARD（未通过），
 * 用户可以进入迁移测试：AI 结合「已掌握知识点」（mastery &gt;= 2 的概念，作为 ANCHOR）出
 * 一道<b>新题</b>（不能与原题一样），用户作答后判分：
 * <ul>
 *   <li>答对 → 降级通过：基础档位升一档（AGAIN→HARD→GOOD，封顶 GOOD，永远拿不到 EASY）；</li>
 *   <li>答错 → 不降级：维持阶段1锁定的档位。</li>
 * </ul>
 *
 * <p>约束（用户决策）：追问设上限 {@code transferMax}（默认 2 轮），防止 AI 无限出题导致对话无法结束；
 * 用户「看答案」（answerRevealedRound 非空）后不再追问。
 */
@Service
public class TransferTestService {

    private static final Logger log = LoggerFactory.getLogger(TransferTestService.class);

    /** 迁移测试默认轮数上限（用户决策：追问要设置上限，不能无限出题） */
    public static final int DEFAULT_TRANSFER_MAX = 2;

    private final DrillRunRepository runRepo;
    private final QuestionBankRepository qbRepo;
    private final ConceptRepository conceptRepo;
    private final MasteryRepository masteryRepo;
    private final QuestionGenerator questionGenerator;
    private final GradingService gradingService;
    private final ProgressContextService progressContext;
    private final ObjectMapper objectMapper;
    private final Random random = new Random();

    public TransferTestService(DrillRunRepository runRepo, QuestionBankRepository qbRepo,
                               ConceptRepository conceptRepo, MasteryRepository masteryRepo,
                               QuestionGenerator questionGenerator, GradingService gradingService,
                               ProgressContextService progressContext, ObjectMapper objectMapper) {
        this.runRepo = runRepo;
        this.qbRepo = qbRepo;
        this.conceptRepo = conceptRepo;
        this.masteryRepo = masteryRepo;
        this.questionGenerator = questionGenerator;
        this.gradingService = gradingService;
        this.progressContext = progressContext;
        this.objectMapper = objectMapper;
    }

    /**
     * 开始迁移测试（阶段3）：校验资格 → 结合已掌握知识点生成新题 → 暂存到 run → phase=TRANSFER_TEST。
     *
     * @return 迁移测试题视图（stem 等），前端据此展示新题并让用户作答
     */
    @Transactional
    public TransferView start(Long userId, Long runId) {
        DrillRun run = requireTransferable(userId, runId);
        QuestionBank q = qbRepo.findById(run.getQuestionId())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "题目不存在"));

        // 当前概念（PRIMARY）→ 已掌握概念（ANCHOR，mastery >= 2）→ 组合成新题
        Long primaryId = q.getConceptIds() != null && q.getConceptIds().length > 0
                ? q.getConceptIds()[0].longValue()
                : null;
        Concept primary = primaryId == null ? null : conceptRepo.findById(primaryId).orElse(null);
        if (primary == null) {
            throw new ResponseStatusException(BAD_REQUEST, "题目缺少主概念，无法生成迁移测试题");
        }

        List<ConceptRef> refs = new ArrayList<>();
        refs.add(ConceptRef.of(primary, ConceptRole.PRIMARY));
        Concept anchor = pickMasteredAnchor(userId, primaryId);
        if (anchor != null) {
            refs.add(ConceptRef.of(anchor, ConceptRole.ANCHOR));
        }
        SelectedTask task = new SelectedTask(refs);

        // 迁移测试要求 arity >= 2（结合已掌握知识点），用对比/串联类认知动作；
        // 若无可用锚点则退化为单概念场景题。
        ProbeType probe = refs.size() >= 2
                ? (random.nextBoolean() ? ProbeType.CONTRAST : ProbeType.INTEGRATION)
                : ProbeType.SCENARIO;

        // 学习上下文：学生进度 + 概念要点（含已掌握锚点说明）
        String context = progressContext.contextFor(userId, task.conceptIds());
        if (anchor != null) {
            String hint = (context == null ? "" : context + "\n\n")
                    + "本次是「迁移测试」：结合已掌握知识点「" + anchor.getName() + "」考察当前知识点「"
                    + primary.getName() + "」。题目必须让学习者把两个知识点联系起来（对比差异、串联链路或应用场景），"
                    + "证明他不仅记住了当前知识点，还能在已有知识体系里迁移运用。";
            context = hint;
        } else {
            context = (context == null ? "" : context + "\n\n")
                    + "本次是「迁移测试」：考察当前知识点「" + primary.getName()
                    + "」的应用与理解，提问角度必须与之前的主问明显不同。";
        }

        // 避免与原题雷同：把原题题干注入 prompt
        List<String> avoidStems = List.of(q.getStem());

        GeneratedQuestion gq = questionGenerator.generate(task, probe, ResponseFormat.FREE_TEXT,
                avoidStems, context);

        // 暂存迁移题到 run（不落 question_bank，避免污染题库统计）
        run.setTransferStem(gq.stem);
        run.setTransferPointsJson(serialize(gq));
        run.setTransferConceptIdsJson(serialize(task.conceptIds().stream().map(Long::intValue).toArray(Integer[]::new)));
        run.setPhase(DrillPhase.TRANSFER_TEST);
        runRepo.save(run);

        return new TransferView(runId, gq.stem, run.getTransferCount() + 1, run.getTransferMax());
    }

    /**
     * 作答迁移测试题：判分 + 降级通过升级（GradingService.gradeTransfer）。
     */
    @Transactional
    public GradeView answer(Long userId, Long runId, String rawAnswer) {
        DrillRun run = runRepo.findByUserIdAndId(userId, runId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "作答不存在"));
        if (run.getPhase() != DrillPhase.TRANSFER_TEST) {
            throw new ResponseStatusException(BAD_REQUEST, "当前不在迁移测试阶段，请先发起迁移测试");
        }
        if (run.getStatus() != DrillRunStatus.GRADED) {
            throw new ResponseStatusException(BAD_REQUEST, "当前作答状态不可迁移测试: " + run.getStatus());
        }
        return gradingService.gradeTransfer(userId, runId, rawAnswer);
    }

    /** 校验迁移测试资格：已判分、教学阶段、未达上限、未看答案、基础档位未通过。 */
    private DrillRun requireTransferable(Long userId, Long runId) {
        DrillRun run = runRepo.findByUserIdAndId(userId, runId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "作答不存在"));
        if (run.getStatus() != DrillRunStatus.GRADED) {
            throw new ResponseStatusException(BAD_REQUEST, "尚未判分，不能开始迁移测试");
        }
        if (run.getPhase() == DrillPhase.TRANSFER_TEST) {
            throw new ResponseStatusException(BAD_REQUEST, "迁移测试已在进行中");
        }
        if (run.getPhase() == DrillPhase.DONE) {
            throw new ResponseStatusException(BAD_REQUEST, "本次练习已结束");
        }
        if (run.getTransferCount() >= run.getTransferMax()) {
            throw new ResponseStatusException(BAD_REQUEST, "迁移测试已达上限（" + run.getTransferMax() + " 轮）");
        }
        // 看答案后不再追问（用户决策：看答案就不继续追问了）
        if (run.getAnswerRevealedRound() != null) {
            throw new ResponseStatusException(BAD_REQUEST, "已看答案，不再继续追问");
        }
        // 基础档位已通过（GOOD/EASY）则无需迁移测试
        String base = run.getFirstGrade();
        if (base != null && (base.equals("GOOD") || base.equals("EASY"))) {
            throw new ResponseStatusException(BAD_REQUEST, "基础作答已通过，无需迁移测试");
        }
        // 综合检测（assessment）不启用迁移测试：它本身就是全知识点的综合考察
        DrillPurpose purpose = run.getPurpose();
        if (purpose == DrillPurpose.CONCEPT_ASSESSMENT || purpose == DrillPurpose.LEVEL_ASSESSMENT) {
            throw new ResponseStatusException(BAD_REQUEST, "综合检测不提供迁移测试");
        }
        return run;
    }

    /** 挑一个已掌握（mastery >= 2）且非当前 PRIMARY 的概念作为迁移测试锚点；无则返回 null。 */
    private Concept pickMasteredAnchor(Long userId, Long primaryId) {
        List<Mastery> mastered = masteryRepo.findByUserId(userId);
        for (Mastery m : mastered) {
            if (m.getMasteryLevel() >= 2 && !m.getConceptId().equals(primaryId)) {
                Concept c = conceptRepo.findById(m.getConceptId()).orElse(null);
                if (c != null) return c;
            }
        }
        return null;
    }

    private String serialize(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("迁移测试题序列化失败", e);
        }
    }

    /** 迁移测试题视图：题干 + 本轮序号 + 上限（前端据此决定是否还能再追一轮）。 */
    public record TransferView(Long runId, String stem, int transferCount, int transferMax) {
    }
}
