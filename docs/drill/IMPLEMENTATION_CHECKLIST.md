# drill 模块实现清单（P0–P5）

> 用途：边建边学的 checklist。每阶段独立可验收，按 P0→P5 推进。
> 铁律（贯穿全程）：**教学决策权在服务端确定性算法，LLM 只做教具**（出题 / 评分 FREE_TEXT / 追问）。凡是能算清的（选哪题、去不去重、分怎么算、闸门怎么拦、间隔多久）都不许交给 LLM 自由发挥。
> 模块边界：新建 `modules/drill`，**不动 `interview` 模块**。鉴权用 JWT，两条 SecurityFilterChain 不波及同事模块，`userId` 只来自 token 的 `sub`。

---

## P0 地基：能跑通「做一道题」

**目标**：建好工程骨架 + 五张核心表 + 鉴权，端到端跑通 LEARN 模式一道 FREE_TEXT 题（happy path）。

### P0.1 修现有仓库三处 bug（`app/src/main/resources/application.yml`）
- `spring.jpa.hibernate.ddl-auto: create-drop` → 改 `validate`（与 Flyway 冲突，会清库）
- `skill` 缩进错误（YAML 层级错位）→ 修正缩进
- kimi 输出的中文破折号导致解析失败 → 加清洗或换模型

### P0.2 加依赖（`app/build.gradle`）
- `spring-boot-starter-security`
- `jjwt` (jjwt-api / jjwt-impl / jjwt-jackson)
- （Flyway 应已存在）

### P0.3 模块目录结构
```
modules/drill/
  web/DrillController.java
  service/{SelectionService,QuestionService,AnswerService,GradingService,ProfileService,ScheduleService}.java
  grader/{Grader,GraderText,GraderMcq,GraderStructured,GraderCode}.java
  domain/{Concept,QuestionBank,DrillRun,GradeResult,Mastery}.java
  ai/{QuestionGenerator,FollowupGenerator}.java
  config/SecurityConfig.java
```

### P0.4 表 DDL（Flyway `V2__drill_schema.sql`，Postgres）
```sql
CREATE TABLE concept (
  id BIGSERIAL PRIMARY KEY,
  topic VARCHAR(64) NOT NULL,
  layer SMALLINT NOT NULL CHECK (layer BETWEEN 1 AND 5),
  name VARCHAR(128) NOT NULL,
  UNIQUE (topic, layer)
);

CREATE TABLE question_bank (
  id BIGSERIAL PRIMARY KEY,
  concept_ids BIGINT[] NOT NULL,          -- arity = array length，不是类别
  probe_type VARCHAR(16) NOT NULL,        -- RECALL/CLOZE/REVERSE/TRAP/SCENARIO/CONTRAST/INTEGRATION
  answer_mode VARCHAR(8) NOT NULL DEFAULT 'WRITE',   -- 暂只用 WRITE，SPEAK 前向兼容保留
  response_format VARCHAR(16) NOT NULL,   -- FREE_TEXT/CHOICE/STRUCTURED/CODE
  arity SMALLINT NOT NULL CHECK (arity BETWEEN 1 AND 3),
  stem TEXT NOT NULL,
  points JSONB,                           -- 该格标准得分点 [{point, weight}]
  mcq_options JSONB,                      -- response_format=CHOICE 时非空
  answer_schema JSONB,                    -- response_format=STRUCTURED 时非空（白名单字段）
  code_ref VARCHAR(32),                   -- response_format=CODE 时存力扣题号
  embedding VECTOR(1536),                 -- 去重兜底用
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE drill_run (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL,
  question_id BIGINT NOT NULL REFERENCES question_bank(id),
  mode VARCHAR(10) NOT NULL DEFAULT 'LEARN',   -- LEARN / REHEARSAL
  answer_mode VARCHAR(8) NOT NULL DEFAULT 'WRITE',
  timing_mode VARCHAR(10) NOT NULL DEFAULT 'NONE', -- NONE / COUNTDOWN
  open_book BOOLEAN NOT NULL DEFAULT FALSE,
  active_seconds INT,
  status VARCHAR(12) NOT NULL DEFAULT 'READY',     -- READY/ANSWERING/SUBMITTED/GRADED/PARKED
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
-- 物理闸门：同一用户同一题只允许一个未闭环 run
CREATE UNIQUE INDEX uq_open_run ON drill_run (user_id, question_id, status)
  WHERE status IN ('READY','ANSWERING');

CREATE TABLE grade_result (
  id BIGSERIAL PRIMARY KEY,
  run_id BIGINT NOT NULL REFERENCES drill_run(id),
  question_id BIGINT NOT NULL REFERENCES question_bank(id),
  answer_hash VARCHAR(64),                -- 防重复提交 / 去重
  by_concept JSONB NOT NULL,              -- 统一判分格式，见 P1.4
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX ON grade_result (question_id, answer_hash);

CREATE TABLE mastery (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL,
  concept_id BIGINT NOT NULL REFERENCES concept(id),
  mastery_level SMALLINT NOT NULL DEFAULT 0 CHECK (mastery_level BETWEEN 0 AND 3),
  last_grade VARCHAR(8),
  due_at TIMESTAMP,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (user_id, concept_id)
);
```

### P0.5 鉴权（`SecurityConfig.java`）
- 两条 `SecurityFilterChain`：① `/api/drill/**` 走 JWT；② 其余放行/同事模块不动
- `userId` 从 `Jwt.getSubject()` 取，**绝不由前端传**
- 入参 `@AuthenticationPrincipal` 取 userId

### P0.6 端点契约（`DrillController.java`）
```java
POST /api/drill/next     -> SelectionService.pickNext(userId) + QuestionService.generate(task)
POST /api/drill/{runId}/submit  -> AnswerService.submit(runId, rawAnswer, timing) -> GradingService.grade(...)
```

### P0.7 坑
- Flyway 与 `create-drop` 冲突 → 必须 `validate`
- `userId` 别信前端，只信 token `sub`
- JSONB 字段用 Jackson 映射，别手拼字符串

### P0.8 验收
- 启服务，手动插 1 条 concept + 1 条 question_bank
- 调 `/next` 拿到题 → `/submit` 拿回判分 → `mastery` 行生成、`due_at` 有值
- 并发对同一题发两次 `/next` 只有一个 `READY/ANSWERING` run（唯一索引拦截）

---

## P1 出题 + 判分

**目标**：4D 签名出题 + 三闸去重 + `Grader` 策略接口（先 FREE_TEXT / CHOICE 两档）。

### P1.1 四维正交签名（题目的本质，不是类别）
`(concept_ids[], probe_type, answer_mode, response_format)` —— arity 是 `concept_ids[]` 的长度，是字段不是分类。

probe_type × arity 合法范围（maxArity=3）：
| probe_type | arity 范围 | 说明 |
|---|---|---|
| RECALL | 1 | 回忆定义/机制 |
| CLOZE | 1 | 填空补全 |
| REVERSE | 1–2 | 反向（给结论要前提） |
| TRAP | 1–3 | 陷阱/易错点 |
| SCENARIO | 1–3 | 场景题 |
| CONTRAST | 2–3 | 对比（必多概念） |
| INTEGRATION | 2–3 | 综合（必多概念） |

response_format：FREE_TEXT / CHOICE / STRUCTURED / CODE（MCQ 与 SPEAK 已从 probe_type 移出，是作答形态）。

### P1.2 出题去重三闸（`QuestionService.generate`，伪代码）
```
1. 硬闸：SELECT probe_type FROM question_bank WHERE concept_ids @> ? 
       挑一个"本概念集还没用过"的 probe_type
2. 软闸：把该 concept 历史 stem 注入 LLM prompt 作负向上下文，要求"不得雷同"
3. 硬兜底：新题 embedding 与历史比 cosine，>0.85 则重新生成（最多 3 次）
```
arity 不会自动长大 → 用比例驱动：当 PRIMARY 概念已掌握（mastery>=2）时，强制 arity>=2 引入一个已掌握锚点（"挂靠学习"）。

### P1.3 出题生成器契约（`QuestionGenerator.java`，复用既有 `StructuredOutputInvoker`）
```java
interface QuestionGenerator {
  QuestionBank generate(Signature sig);  // sig = 4D 签名；LLM 只填 stem/points/options/schema
}
```
- 复用 `WebSearchClient`（RestClient 直连 dashscope）拿检索，不用 `StructuredOutputInvoker`（它丢 ChatResponse 拿不到 search_info）

### P1.4 判分统一格式（所有 Grader 输出同一结构）
```json
{ "byConcept": [
  { "conceptId": 42, "role": "PRIMARY",
    "pointResults": [ {"point":"...","verdict":"HIT|PARTIAL|MISS","evidence":"用户原话片段"} ],
    "extraCorrect": ["..."], "factualErrors": ["..."] }
] }
```
`evidence` 必填且必须是用户原话片段（防 LLM 脑补）。分数由服务端算，LLM 只给逐点 verdict。

### P1.5 Grader 策略接口（按 response_format 分派，自身零 if）
```java
interface Grader { GradeResult grade(GradeRequest req); }
// GradeRequest { responseFormat, rawAnswer, questionBank(含 points/options/schema), runId }
```
| 实现 | 算法 | 走 LLM? |
|---|---|---|
| GraderText | LLM 逐 point 判 HIT/PARTIAL/MISS | 是 |
| GraderMcq | 精确比对选项 | **否，毫秒级** |
| GraderStructured | 按 schema 字段逐条 rubric | 是（判字段满足） |
| GraderCode | relay 力扣拿 verdict 再映射 | **否** |

### P1.6 坑
- **MCQ / CODE 绝不能用 LLM 判分**（善意脑补会废掉痛点 3 的诚实画像）
- `evidence` 为空视为判分无效，必须重判
- CHOICE 的 `mcq_options` 正确答案只存服务端，前端不下发

### P1.7 验收
- 同一 FREE_TEXT 题，GraderText 与人工判分相关系数 ≥ 0.8
- MCQ 100% 精确；去重三闸下连续 20 次出题无雷同（embedding<0.85）

---

## P2 闸门 + 深度画像

**目标**：drill_run 状态机推进 + 部分唯一索引物理闸门 + 深度画像查询。

### P2.1 状态机（`AnswerService` 推进，不靠索引号）
`READY → ANSWERING → SUBMITTED → GRADED`；72h 无心跳自动 `PARKED`（定时任务扫 `updated_at`）。
- 不限时；记 `active_seconds` 心跳；开卷标 `open_book=true`

### P2.2 物理闸门（已在 P0.4 建唯一索引 `uq_open_run`）
- 超发或并发 → DB 抛唯一约束异常 → 服务端转 HTTP 409，不让 prompt 规则兜底（prompt 拦不住并发）

### P2.3 深度画像（`ProfileService`，直出，不建 Elo）
```sql
SELECT topic, MAX(layer) AS depth
FROM mastery m JOIN concept c ON m.concept_id = c.id
WHERE m.user_id = ? AND m.mastery_level >= 2
GROUP BY topic;
```
- layer 本身即难度刻度，无需 Elo

### P2.4 坑
- 状态推进靠枚举状态，不靠 `index+1`（那是 interview 模块痛点根源，别照搬）
- 并发提交靠唯一索引，不靠应用层锁

### P2.5 验收
- 同一题并发提交只产生一个未闭环 run（409 验证）
- 画像查询返回每个 topic 的已掌握最深 layer，与手算一致

---

## P3 间隔排程

**目标**：FSRS 五档表 + `IntervalScheduler` 接口（五档先上，留扩展）。

### P3.1 接口
```java
interface IntervalScheduler {
  Duration nextInterval(Grade grade, boolean timed);
}
// Grade: AGAIN / HARD / GOOD / EASY / REHEARSAL_PASS（五档）
```

### P3.2 计时 opt-in（fail-safe）
- 计时只贡献 `EASY` 一档；未计时走保守间隔（间隔偏短，宁可常复习）
- `timed=false` 不报错、不丢数据，只是排得密

### P3.3 坑
- 别把计时当硬门槛，否则用户某天没空就断链
- 五档表先用常量实现，`IntervalScheduler` 留接口便于换真实 FSRS

### P3.4 验收
- `GOOD` 间隔 > `HARD` > `AGAIN`；`EASY`(计时) 间隔最长
- 未计时时 `EASY` 退化为接近 `GOOD`（保守）

---

## P4 REHEARSAL（模拟面试，纯文本，无语音）

**目标**：`drill_run.mode='REHEARSAL'` + LLM 追问（封顶 2 轮），训练「提取/组织/扛追问」。

### P4.1 场景规则
- 闸门：`mastery_level >= 2` 才开放
- 强制 `open_book=false` + `COUNTDOWN` + 提交后不可编辑（复用 P2 状态机 + P3 计时）
- 首答后 `FollowupGenerator` 生成深挖/反驳追问，封顶 **2 轮**，超时/答不出按 AGAIN，不阻塞

### P4.2 追问生成器契约（`FollowupGenerator.java`）
```java
interface FollowupGenerator {
  String nextQuestion(String prevAnswer, int round);  // round<=2
}
```
- 复用 `QuestionGenerator` 的 LLM 调用基础设施

### P4.3 多轮判定
- 每轮答案走现成 `GraderText`（per-concept 判分）
- 全部轮 content 非 MISS → `mastery_level` 升 **3（模拟面试达标）**；否则维持 2 + 进复习

### P4.4 坑
- `content✗` 才降/不升 mastery；流畅度（已删）绝不阻塞升级
- 追问封顶 2 轮，防递归

### P4.5 验收
- REHEARSAL 与 LEARN 写模式 content 判分一致性 ≥ 0.8
- 用户在 REHEARSAL 扛住 ≥1 轮追问且 content 通过的比例（产品核心指标）

---

## P5 内化（笔记没过脑子）

**目标**：用 JSON schema 物理上写不出「标准笔记」，逼用户自己重构。

### P5.1 笔记 schema 契约
- 笔记 JSON **禁止** `summary` / `correctAnswer` 字段（服务端校验白名单）
- 只允许：用户自己的复述、关联 concept、置信度
- LLM 生成笔记时也受同 schema 约束（结构化输出强制）

### P5.2 坑
- 前端拿到的 schema 必须服务端校验，禁止任意字段渲染（防 XSS / 字段膨胀）
- 白名单字段类型仅 string/enum/number/嵌套对象，深度 ≤ 2

### P5.3 验收
- 尝试提交含 `summary` 的笔记被拒
- 同知识点 STRUCTURED 与 FREE_TEXT 掌握度判定一致性 ≥ 0.75

---

## 学习节奏建议
- 每阶段先读对应概念卡（落 `docs/drill/concept-cards/`），再写码，再回来对验收标准自测
- 卡住开新对话说「继续 Day N + 阶段名」即可恢复（本文件即外部化进度）
- 概念卡模板：定义 → 机制 → 项目印证 → 对比 → 易错点/面试陷阱 → 记忆锚点
