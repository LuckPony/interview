# 学习型面试 Agent —— 设计说明书 v1

> 状态：需求已确认，待逐步实现
> 基线仓库：`interview`（Spring Boot + Java 21 + PostgreSQL/pgvector + Redis + MinIO + Spring AI）
> 最后更新：2026-08-08

---

## 0. 核心主张（全文的地基）

现有 AI 学习工具的一切毛病，根源是**把"当老师"这件事交给了 LLM**。LLM 没有你的长期状态、不知道你的知识边界、也没有动机拦住你说"这题你还没懂，不许走"，它只有"生成下一段合理文本"的本能。

本项目的架构主张：

> **教学决策权归服务端的确定性算法，LLM 降级为教具。**
> LLM 只做三件事：按指令生成内容、按 rubric 打分、讲解与追问。
> "学什么 / 多难 / 何时复习 / 能否进下一题" 这四个决策，一律不进 Prompt。

| 决策 | 归属 | 实现 |
|---|---|---|
| 学什么 | 服务端 | 知识图谱前沿选点 |
| 多难 | 服务端 | Elo 能力模型 |
| 何时复习 | 服务端 | FSRS 记忆调度 |
| 能否进下一题 | 服务端 | 掌握闸门状态机 |
| 题目文本 / 评分 / 讲解 | LLM | Spring AI 多 Provider |

---

## 1. 已确认的产品决策

| 项 | 决策 | 架构含义 |
|---|---|---|
| 产品定位 | 单人工具，但支持用户登录 | 所有业务表**第一天就带 `user_id`**；认证只做本地账号 + JWT；不做多租户隔离/配额/审核 |
| 前端 | 新建独立 SPA 项目 | 与 Java 仓库分离；REST + SSE 通信；后端保持纯 API |
| 语音口述 | 推迟到 P5 | MVP 用「限时 + 提交后不可编辑」的文字模式模拟不可撤回压力，成本为零 |
| 知识图谱冷启动 | 手工种一棵 + 逐步扩 | YAML 维护，Flyway/启动时导入；离线 Agent 批产放到 P3 之后 |

### 待定参数（未确认前按默认值实现）

| 参数 | 默认值 | 影响 |
|---|---|---|
| 图谱种子领域 | Java 后端（JVM / 并发 / Spring / 数据库 / 分布式 / 网络） | 首棵图谱的根节点集合 |
| 前端技术栈 | Vite + Vue 3 + TypeScript | 仅影响前端仓库 |
| 每日学习时长预算 | 45 分钟（约 8 个训练单元） | `daily_plan` 的配额上限 |

---

## 2. 领域模型

### 2.1 命名与包结构

新增模块放在 `interview.homegrown.modules` 下，与既有 `resume` / `interview` 平级：

```
interview.homegrown
├── common
│   ├── ai            (既有：LlmProviderRegistry / StructuredOutputInvoker)
│   ├── security      (新增：JWT + CurrentUser)
│   └── ...
├── infrastructure    (既有：file / redis)
└── modules
    ├── resume        (既有，复用)
    ├── interview     (既有，改造为 Pressure 模式的入口)
    ├── graph         (新增：知识图谱)
    ├── assessment    (新增：摸底 CAT + 能力模型)
    ├── planner       (新增：FSRS + 每日计划)
    ├── drill         (新增：单题掌握闸门状态机)
    ├── question      (新增：题目工厂 + 题库)
    ├── grader        (新增：判分策略)
    └── note          (新增：概念卡片)
```

### 2.2 表设计（V2 migration 目标）

所有表默认带 `user_id BIGINT NOT NULL`，除 `concept` / `concept_edge` / `question` / `rubric`（这四张是全局共享内容，不属于某个用户）。

#### 用户

```sql
app_user(
  id, username UNIQUE, password_hash, display_name,
  target_role,              -- 目标岗位，用于 JD gap 分析
  daily_budget_minutes,     -- 每日时长预算
  created_at
)
```

#### 知识图谱（全局）

```sql
concept(
  id, code UNIQUE,          -- 稳定标识，如 'jvm.gc.g1'
  name, domain,             -- 一级领域：jvm / concurrency / spring / db / distributed / network
  layer,                    -- CONCEPT | MECHANISM | IMPLEMENTATION | TRADEOFF | FAILURE
  difficulty,               -- 1-5，图谱固有难度（与题目难度 b 区分）
  summary,                  -- 一句话定位，不是答案
  source_refs JSONB,        -- [{type:'doc'|'book'|'src'|'interview', ref:'...', locator:'...'}]
  created_at
)

concept_edge(
  id, from_concept_id, to_concept_id,
  edge_type                 -- PREREQ（前置）| PARENT（归属）| RELATED（相关）| CONTRAST（易混淆）
)
```

> `CONTRAST` 边很关键：它是 CONTRAST 型复习题的数据来源（"X vs Y 什么时候选谁"）。

#### 用户 × 概念状态（**核心表**）

```sql
user_concept_state(
  id, user_id, concept_id,
  theta            REAL,     -- Elo 能力值，初始 1200
  mastery_level    VARCHAR,  -- UNSEEN | LEARNING | WRITE_OK | SPEAK_OK | MASTERED | LAPSED
  -- FSRS 状态
  stability        REAL,
  fsrs_difficulty  REAL,
  last_review_at   TIMESTAMP,
  next_due_at      TIMESTAMP,
  reps             INTEGER,
  lapses           INTEGER,
  -- 反重复
  last_probe_types VARCHAR[], -- 最近 3 次用过的题型，用于强制轮换
  UNIQUE(user_id, concept_id)
)
```

#### 题库（全局）

```sql
question(
  id, concept_id, question_type, probe_type,
  difficulty_b   REAL,       -- Elo 题目难度，会被众包校准
  stem           TEXT,       -- 题干
  payload        JSONB,      -- 按 type 存：MCQ 选项 / CODE 力扣题号 / DEBUG 代码片段
  key_points     JSONB,      -- 标准要点清单（供批改比对，不直接展示）
  rubric_id      BIGINT,
  source_ref     JSONB NOT NULL,  -- 强约束：无来源不入库
  gen_model      VARCHAR,
  review_status  VARCHAR,    -- DRAFT | APPROVED | REJECTED
  created_at
)

rubric(
  id, name, dimensions JSONB  -- [{key, label, weight, anchors:{0:'',1:'',2:'',3:'',4:''}}]
)
```

`question_type` 枚举：`QA | MCQ | CLOZE | CODE | SYSTEM_DESIGN | DEBUG | TRACE | SPEAK`
`probe_type` 枚举：`RECALL | CLOZE | REVERSE | CONTRAST | SCENARIO | TRAP | SPEAK`

#### 训练执行（单题多轮）

```sql
drill_session(
  id UUID, user_id, plan_id, question_id, concept_id,
  state,                     -- ASKING | ANSWERING | JUDGING | HINTING | INTERNALIZING | PASSED | ABORTED
  mode,                      -- WRITE | SPEAK | PRESSURE
  attempt_count, max_hint_level,
  started_at, passed_at
)

drill_turn(
  id, drill_session_id, turn_index,
  role,                      -- SYSTEM_QUESTION | USER_ANSWER | AI_HINT | AI_JUDGE | USER_RECALL | AI_CRITIQUE
  content TEXT,
  score_json JSONB,          -- 判定轮：各维度分数
  hint_level INTEGER,
  latency_ms INTEGER,        -- 首次出声/首次落键延迟
  created_at
)
```

#### 笔记

```sql
concept_card(
  id, user_id, concept_id,
  -- 6 段模板
  definition, mechanism, project_evidence, comparison, pitfalls, mnemonic,
  version INTEGER,
  ai_critique JSONB,         -- AI 只写"你漏了什么/哪句错了"，不写正文
  status,                    -- DRAFT | REVISED | FINAL
  created_at, updated_at
)

cloze_item(
  id, user_id, card_id, field_name, masked_text, answer_text,
  -- 复用 FSRS 字段
  stability, fsrs_difficulty, next_due_at, reps, lapses
)
```

> **硬约束**：`project_evidence`（项目印证）字段**不允许由 LLM 填写**，必须由用户手填并关联到简历中的真实项目。服务端在保存时校验来源标记。

#### 计划与能力快照

```sql
daily_plan(
  id, user_id, plan_date, budget_minutes,
  items JSONB,               -- [{questionId, conceptId, source:'DUE'|'FRONTIER'|'WEAK'|'PROBE', estMinutes}]
  status, generated_at
)

ability_snapshot(
  id, user_id, domain, theta, sample_count, snapshot_date
)
```

---

## 3. 关键算法

### 3.1 Elo 能力模型

```
期望得分  E = 1 / (1 + 10^((b − θ) / 400))
用户更新  θ' = θ + K_u × (S − E)          K_u = 32（早期）→ 16（稳定后）
题目更新  b' = b − K_q × (S − E)          K_q = 4
```

- `S` = 本题归一化得分 `[0,1]`，由 rubric 加权得出。
- 使用了 L3/L4 提示的，`S` 打折（L3 × 0.6，L4 × 0.3）。
- 领域 θ = 该领域下所有 concept θ 的加权平均（按 concept.difficulty 加权）。

### 3.2 FSRS 记忆调度

**不要自己调参**。直接移植 fsrs4anki 的默认权重表（FSRS-4.5，17 个参数），只实现三个函数：

```
可提取性  R(t, S) = (1 + F × t / S)^D          F = 19/81, D = −0.5
下次间隔  I = (S / F) × (R_target^(1/D) − 1)   R_target 默认 0.9
复习后更新 S、difficulty 按 FSRS 状态转移公式（分 again/hard/good/easy 四档）
```

四档评级由判定分自动映射：

| 归一化得分 | 评级 | 说明 |
|---|---|---|
| < 0.4 或用了 L4 | again | 计一次 lapse |
| 0.4 – 0.6 | hard | |
| 0.6 – 0.85 | good | |
| > 0.85 且无提示 | easy | |

### 3.3 摸底测验（简化 CAT）

对每个一级领域独立跑一次：

1. 从难度 3 起步，抽 1 道 MCQ 或短 QA。
2. 答对 → 难度 +1；答错 → 难度 −1；连续同向两次则步长翻倍。
3. 达到 12 题或难度连续 3 次在同一档震荡 → 收敛。
4. 输出该领域初始 θ = `1000 + 100 × 收敛难度`，并把探测过的 concept 标为 `LEARNING`。

全部领域跑完约 20 分钟，产出雷达图。**这是新用户的第一个动作**。

### 3.4 评分 rubric（6 维，每维 0–4）

| 维度 | 权重 | 3 分锚点示例 |
|---|---|---|
| 正确性 | 0.30 | 无事实错误，但个别表述不精确 |
| 完整性 | 0.20 | 覆盖主要要点，漏 1 个次要点 |
| 深度 | 0.20 | 说清机制，未触及权衡与失效场景 |
| 结构 | 0.10 | 有条理，但未做到结论先行 |
| 术语 | 0.10 | 术语基本准确，个别用词口语化 |
| 应用迁移 | 0.10 | 能举例，但未结合自身项目 |

工程约束：
- 评分调用**固定 `temperature = 0`**，走独立于出题的 Provider 通道。
- 对 `(question_id, sha256(answer))` 做结果缓存，保证同一答案分数可复现。
- Prompt 中固定注入 `key_points` 与 anchors，禁止模型自由发挥评分标准。
- 输出结构：`{dimensions: [{key, score, evidence}], normalized, missing_points[], wrong_points[]}`。

### 3.5 前沿选点（Frontier）

```
候选 = { c ∈ concept |
         user_concept_state(c).mastery_level ∈ {UNSEEN, LEARNING}
         ∧ ∀ p ∈ prereq(c): mastery_level(p) ≥ WRITE_OK
         ∧ |c.difficulty − θ_domain 折算难度| ≤ 1 }
排序 = 与目标岗位 JD 的相关度 × 邻域已掌握密度
```

**盲区探照灯**：每周一次，选出覆盖率（`已触达节点 / 子树节点总数`）最低且与 JD 强相关的子树，插入 3 道 MCQ 探针。这是唯一能捅破"不知道自己不知道"的机制。

### 3.6 每日计划生成

`@Scheduled` 每日 04:00 生成，也支持手动重算：

| 来源 | 配额 | 说明 |
|---|---|---|
| DUE | 60% | `next_due_at <= today` 的复习项，按逾期天数降序 |
| FRONTIER | 30% | 前沿选点的新概念 |
| WEAK | 10% | θ 最低的 3 个 concept，出综合应用题 |
| PROBE | 每周一次 | 盲区探照灯 |

**变形约束**：为 DUE 项选题时，`probe_type NOT IN user_concept_state.last_probe_types`，且优先选与上次不同 `question_type` 的题。选不出来就现场生成一道新变体。

---

## 4. 单题掌握闸门（痛点 5 + 7 的核心）

### 4.1 状态机

```
ASKING ──► ANSWERING ──► JUDGING ──┬── 未达标 ──► HINTING ──► ANSWERING （回环）
                                    └── 达标 ──► INTERNALIZING ──► PASSED
```

- **后端不提供"跳到下一题"的接口**。`GET /drill/next` 在当前 session 未 `PASSED` 时返回 409。这是硬保险，AI 想推也推不动。
- 用户可无限次发起 `POST /drill/{id}/ask`（我还有疑问），不影响状态。
- 只有 `POST /drill/{id}/confirm` 才能从 `INTERNALIZING` 进入 `PASSED`。

### 4.2 渐进提示分级

| 级别 | 给什么 | 代价 |
|---|---|---|
| L1 | 方向提示（"想想这发生在哪块内存区"） | S × 0.9 |
| L2 | 关键概念名 | S × 0.75 |
| L3 | 半个答案 / 推导起点 | S × 0.6 |
| L4 | 完整讲解 | S × 0.3，且强制 FSRS 评级为 again |

每次 `POST /drill/{id}/hint` 提升一级，不可跳级。

### 4.3 内化环节（笔记必须由用户产出）

1. 系统展示 concept card 的 6 段空模板（若已有卡片则展示旧版）。
2. 用户填写；`project_evidence` 字段**服务端强制校验非 AI 生成**（前端该字段禁用任何 AI 补全入口）。
3. `POST /card/{id}/critique` → AI 对照 `key_points` 只输出：`missing[]`（你漏了什么）、`wrong[]`（哪句说错了）、`vague[]`（哪句太空）。**Prompt 中明令禁止输出完整答案或改写用户句子**。
4. 用户修订一次 → `status = FINAL` → 自动为每个非空字段生成 `cloze_item` 进入 FSRS 队列。

### 4.4 API 契约草案

```
POST   /api/drill/start          {planItemId}          → drillSession
GET    /api/drill/{id}                                 → 当前状态 + 可用动作
POST   /api/drill/{id}/answer    {content, elapsedMs}  → 判定结果（不含答案）
POST   /api/drill/{id}/hint                            → 下一级提示
POST   /api/drill/{id}/ask       {question}            → 自由追问（SSE 流式）
POST   /api/drill/{id}/recall    {summary}             → 提交自述，触发批改
POST   /api/drill/{id}/confirm                         → 确认掌握，状态转 PASSED
GET    /api/drill/next                                 → 409 若当前未 PASSED
```

---

## 5. 判分策略（痛点 6）

```java
public interface Grader {
    QuestionType supports();
    GradeResult grade(Question q, Submission s, GradeContext ctx);
}
```

Spring 注入 `Map<QuestionType, Grader>`，`GraderDispatcher` 按 type 分发。

| type | 实现 | 备注 |
|---|---|---|
| QA | `LlmRubricGrader` | temperature=0 + key_points + anchors |
| MCQ | `ExactMatchGrader` | 零成本，摸底与探针专用 |
| CLOZE | `SemanticMatchGrader` | embedding 相似度 + 关键词命中 |
| CODE | `LeetCodeRelayGrader` | **不自建沙箱**，见下 |
| SYSTEM_DESIGN | `ChecklistGrader` | 结构化字段逐项 rubric |
| DEBUG / TRACE | `ExactMatchGrader` + LLM 复核 | 有唯一答案 |
| SPEAK | `SpeechGrader` (P5) | 内容分 + 流畅度分分离 |

### 5.1 算法题：力扣中继模式

系统不做判题，只做**调度与复盘**：

1. 按薄弱考点推荐力扣题号 + 考点标签，给出跳转链接。
2. 用户在 leetcode.cn 完成后回填结果：`AC / TLE / WA / 看了题解`，以及耗时。
3. 系统接管复盘：让用户**口述解法思路**、复述时空复杂度、回答变形追问（"改成求第 k 大呢"、"数据量到 10^9 怎么办"）。这部分走 QA grader。

理由：面试真正考的是"你能不能讲清楚"，不是"你能不能跑过测试"。省掉整套沙箱工程。

### 5.2 架构设计题：结构化提交

不接受一坨自由文本，前端提供分字段表单：

`容量估算 / 组件划分 / 数据模型 / 关键链路 / 瓶颈识别 / 取舍理由 / 失败场景与降级`

每个字段独立 rubric 打分，缺失字段直接 0 分。这样才有区分度，也才训练得出面试时的结构化表达。

---

## 6. Prompt 契约与反幻觉约束

所有 LLM 调用走 `StructuredOutputInvoker`，并遵守：

1. **出题必须带来源**。System prompt 固定包含："题目只能基于下方给定材料，禁止引入材料之外的事实。必须在 `sourceRef` 字段回填你所依据的材料片段标识。" 返回结果中 `sourceRef` 为空 → 判废重试，重试 2 次仍失败 → 该 concept 标记为"素材不足"，进人工补料队列。
2. **评分不得自由发挥标准**。anchors 全文注入，输出必须逐维给 `evidence`（引用用户答案中的原句）。
3. **批改不得代写**。System prompt 明令："只输出缺失点、错误点、含糊点。禁止输出完整答案、禁止改写用户的句子、禁止补全用户未写的段落。"
4. **讲解走苏格拉底式**，按 hint_level 严格限制信息量，L1/L2 阶段禁止出现答案关键词（服务端做关键词过滤兜底）。

---

## 7. 离线知识工厂（P3 之后）

独立进程，**不在请求链路上**。可用 Claude Agent SDK（TS/Python）实现，理由是它擅长长时 agentic 任务与文件系统操作。

```
输入：Spring/JDK 源码目录、PDF 教材、面经语料
  ↓ 自主读文件、抽概念、判层次
输出：concepts.json / edges.json / questions.json（含 source_ref）
  ↓
人工抽检（每批随机 10%）+ 难度初标
  ↓
导入主库，review_status = APPROVED
```

与主站**通过 JSON schema 解耦**，跑挂了不影响线上。

**主链路不引入 Agent SDK**，理由：语言错配（Java vs TS/Python）、能力方向相反（本项目要收回决策权而非增强自主）、Spring AI 已满足需求、线上依赖 Claude 有稳定性成本。

---

## 8. 分期路线与验收标准

| 阶段 | 内容 | 验收标准 | 解决痛点 |
|---|---|---|---|
| **P0** | 用户体系 + 手工图谱导入 + 摸底 CAT + 能力雷达图 | 20 分钟摸底后能输出一张六领域雷达图，θ 落库 | 3 |
| **P1** | 掌握闸门状态机 + 渐进提示 + 自写卡片批改 | 未 PASSED 时 `/drill/next` 稳定返回 409；卡片必须用户先写才有 AI 批注 | 5、7 |
| **P2** | FSRS 调度 + probe_type 轮换 + 每日计划 | 同一 concept 连续 3 次复习出现 3 种不同题型 | 2 |
| **P3** | 图谱扩建 + 前沿选点 + 盲区探照灯 + 材料锚定 | 每道题都能点开看到来源引用；每周产出一次盲区报告 | 1 |
| **P4** | Grader 策略化 + 架构题结构化 + 力扣回填 | 至少 5 种 question_type 可跑通 | 6 |
| **P5** | 语音链路 + 副语言指标 + Pressure 模式 | 输出语速/静默/填充词三条曲线，与内容分分离 | 4 |

P0 + P1 完成即可获得主要体感改善。

---

## 9. 实现前需修复的既有问题

| # | 位置 | 问题 |
|---|---|---|
| 1 | `app/src/main/resources/application.yml` L108–125 | `python / algorithm / system-design / database` 四个 skill 被错误缩进到 `frontend` 之下，实际只有 2 个 skill 生效 |
| 2 | 同文件 `spring.jpa.hibernate.ddl-auto` | 设为 `create-drop`，与 Flyway 版本管理直接冲突（启动重建、关闭删表），应改为 `validate` |
| 3 | 同文件 kimi provider | `kimi—latest` 使用了中文破折号 `—` 而非 ASCII `-` |
| 4 | `InterviewSessionService.submitAnswer()` | 直接 `currentQuestionIndex + 1` 推进 —— 痛点 5 的物理根源，需替换为 `DrillStateMachine.transition()` |
| 5 | `InterviewSessionService` | 题目仅存 Redis（TTL 24h），缓存失效即整场面试不可恢复；应改为 DB 为准、Redis 为缓存 |

---

## 10. 附：概念卡片 6 段模板

| 段 | 要求 | 谁来写 |
|---|---|---|
| 定义 | 一句话说清它是什么、解决什么问题 | 用户 |
| 机制 | 内部怎么工作的，关键步骤 | 用户 |
| 项目印证 | 我在哪个项目的哪个场景用过/踩过 | **仅用户，AI 禁止介入** |
| 对比 | 与最容易混淆的那个东西的边界 | 用户，AI 可提示对比对象 |
| 易错点 | 面试陷阱 / 常见误解 | 用户先写，AI 补漏 |
| 记忆锚点 | 一句能瞬间唤起全卡的话 | 用户选或改，AI 可给候选 |
