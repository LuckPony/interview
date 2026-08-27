# 苏格拉底教学评分体系 —— 施工蓝图

> 本改造是**原子改造**：迁移脚本 / entity / 引用代码 / prompt / 前端必须一次性同步改完，
> 中间任何一态都编译或启动不过（删了列但 entity 还映射 → 启动炸；改了 entity 但代码还引用旧字段 → 编译炸）。
> 因此不能逐文件零散提交，必须按蓝图整块铺开后统一编译验证。

## 评分规则（已与用户确认）

| 项 | 规则 |
|---|---|
| 流程 | 苏格拉底：先答→AI判三态→达标表扬提示结束 / 未达标逐步引导→真不会才给答案+原因→懂后再考查（基于已学/画像/当前知识点，不限题型） |
| 答完判定 | 仅 AI 判定；澄清题意/没进入实质作答=不算答完；开始答且自然停=答完；"不懂了/就这样"=答完 |
| G1 预引导分 | 用户首次答完后判；达标→最终分=G1，可拿 GOOD |
| G2 引导后分 | 未达标→引导→再考查答完后判；最终分=G2 覆盖 G1；封顶 BASIC |
| 达标阈值 | 评分点覆盖 ≥80% 且无致命缺漏 |
| 看答案 | 用户表明真不会→AI 给答案+原因；最终分封 AGAIN |
| 旧机制 | followups 追问 + 补救测试 全部移除 |

## 默认取舍（用户未逐条否认，按建议推进）
- 复习（ReviewService/DrillReview）**本轮不改**，沿用旧评分调用；新 GradingService 改造后复习自动复用
- DrillTurn **改造存**：每轮存 judgeState/coverage/引导问/作答
- 讲解答疑 LessonQaMessage **保留不动**

---

## 阶段 0：数据模型（迁移 + entity）

### V44 迁移脚本 `app/src/main/resources/db/migration/common/V44__socratic_grading.sql`
```sql
-- 苏格拉底教学评分体系：移除补救测试/追问字段，加两级评分字段
-- 删：phase/first_grade/transfer_*（旧三阶段+补救测试）/current_round/max_round/answer_revealed_round（旧追问）
ALTER TABLE drill_run
    DROP COLUMN IF EXISTS phase,
    DROP COLUMN IF EXISTS first_grade,
    DROP COLUMN IF EXISTS transfer_count,
    DROP COLUMN IF EXISTS transfer_max,
    DROP COLUMN IF EXISTS transfer_stem,
    DROP COLUMN IF EXISTS transfer_points,
    DROP COLUMN IF EXISTS transfer_concept_ids;

-- DrillRun 新增苏格拉底评分字段
ALTER TABLE drill_run
    ADD COLUMN socratic_state VARCHAR(20) NOT NULL DEFAULT 'ANSWERING',  -- ANSWERING/GUIDED/DONE
    ADD COLUMN pre_grade VARCHAR(10),      -- G1 预引导分（AGAIN/HARD/GOOD/EASY），诊断用
    ADD COLUMN final_grade VARCHAR(10),    -- 最终分（=G1 或 G2）
    ADD COLUMN guided BOOLEAN NOT NULL DEFAULT FALSE,    -- 是否经过引导
    ADD COLUMN guide_rounds INT NOT NULL DEFAULT 0,      -- 引导轮数
    ADD COLUMN revealed BOOLEAN NOT NULL DEFAULT FALSE;  -- 是否看过答案

-- DrillTurn 加苏格拉底判定字段
ALTER TABLE drill_turn
    ADD COLUMN judge_state VARCHAR(20),   -- answering/needs_guide/done
    ADD COLUMN coverage DECIMAL(4,3),     -- 评分点覆盖度 0~1
    ADD COLUMN fatal_gap BOOLEAN;        -- 是否有致命缺漏

-- GradeResult 加两级评分记录
ALTER TABLE grade_result
    ADD COLUMN pre_grade VARCHAR(10),
    ADD COLUMN final_grade VARCHAR(10),
    ADD COLUMN guided BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN guide_rounds INT NOT NULL DEFAULT 0,
    ADD COLUMN revealed BOOLEAN NOT NULL DEFAULT FALSE;
```

### DrillPhase 枚举重构 `domain/DrillPhase.java`
```java
public enum DrillPhase {
    ANSWERING,   // 用户作答中（含苏格拉底引导循环）
    GUIDED,      // 已进入引导（G1 未达标，正在/已引导）
    DONE         // 已结束
}
```

### DrillRun entity 改造 `domain/DrillRun.java`
- 删字段：`phase`(改用 socratic_state)、`firstGrade`、`transferCount`、`transferMax`、`transferStem`、`transferPointsJson`、`transferConceptIdsJson`、`currentRound`、`maxRound`、`answerRevealedRound`
- 加字段：`socraticState`、`preGrade`、`finalGrade`、`guided`、`guideRounds`、`revealed`
- 注意：`currentRound`/`maxRound` 被 RehearsalService 用 → 复习暂不改，这两个字段**先不删**，只标 @Deprecated，避免复习链路编译炸

### GradeResult entity 改造 `domain/GradeResult.java`
- 加：`preGrade`、`finalGrade`、`guided`、`guideRounds`、`revealed`

### DrillTurn entity 改造 `domain/DrillTurn.java`
- 加：`judgeState`、`coverage`、`fatalGap`

---

## 阶段 1：删旧机制（代码）

- 删 `service/TransferTestService.java`（整个文件）
- 删 `DrillController` 的 transfer 端点：`/{runId}/transfer-question`、`/{runId}/transfer-answer`、auto-transfer 触发逻辑（1113-1230 附近）
- 删 `DrillController` 的 `/{runId}/followup` 端点 + `RehearsalService.spawnFollowup`（REHEARSAL 追问场）
- 删 `GraderText` 对 followups 的处理（105/120 行）
- 删 `GradeGenerator` 的 followups 参数（38/43/118-122 行）
- 删 `FollowupGenerator.java`（如已无引用）
- 删 `QuestionGenerator` prompt 的 followups 段（75-80、99、124-126、139 行）+ GeneratedQuestion.followups 字段
- 删 `DrillController` `/{runId}/followup` 路由 + `extractFollowups`（1505-1510）

---

## 阶段 2：苏格拉底判分

### 新建 `ai/SocraticJudgeService.java`
每轮对话后 AI 结构化判定：
```json
{ "state": "answering|needs_guide|done",
  "coverage": 0.0, "fatal_gap": false,
  "guide_question": "...", "praise": "..." }
```
- answering → AI 简短确认并等
- needs_guide → 抛引导问题（不给答案）
- done → 表扬+提示结束；G1 未达标则触发再考查题生成

### 新建苏格拉底 prompt（system + user）
- 角色定位：苏格拉底导师
- 答完判定口径：澄清题意不算答完、实质作答自然停=答完
- 引导策略：逐步追问不给答案，除非用户明确"不会了"
- 再考查：done 后若 G1 未达标，基于已学/画像/当前知识点出新题

### 改造 `DrillController` 主 chat 流
把判分→引导→再考查并入 `/chat` 端点，每轮调 SocraticJudgeService

---

## 阶段 3：两级评分 `service/GradingService.java`

- G1：首次 answering→done/needs_guide 转换点判；达标→GOOD
- G2：引导后再考查答完后判；封顶 BASIC
- 看答案：revealed=true→封 AGAIN
- 阈值：覆盖 ≥80% 且无致命缺漏
- 删 `gradeTransfer` 方法

---

## 阶段 4：能力画像 md

- Mastery 加字段 `skill_doc`（TEXT，按概念分组的"已训练能力"md）
- 每题结束聚合：conceptId/finalGrade/preGrade/guided/guideRounds/已掌握点[] → 写 skill_doc
- 用户画像页展示 + 下次出题 prompt 注入

---

## 阶段 5：移废表（迁移）

- 复习暂不改 → `drill_review` 保留
- 讲解答疑保留 → `lesson_qa_message` 保留
- `sub_point_pass`：检查 LearningWorkflowService/SelectionService/StudyPlanService 是否还依赖，若无独立用途则删
- 实际可能只删 transfer_* 列（已在阶段 0 V44 删），无整表要删

---

## 原子性说明

阶段 0+1+2+3 必须一次性铺开：
- V44 删列 → entity 删字段 → 引用代码删调用 → prompt 删 followups → 前端删 transfer
- 中间态全部编译/启动不过
- 预计单轮无法完成全量，需多轮分块但每块落地前无法编译验证

策略：本蓝图落定后，下一轮从阶段 0+1（数据模型+删旧机制）一次性铺开，编译通过即锁档；再下一轮阶段 2+3（判分+评分）；最后阶段 4+5。
