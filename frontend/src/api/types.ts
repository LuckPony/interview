// 后端 DTO 的前端映射。字段名严格对齐 DrillController / 各 *View。

export interface LoginResp {
  token: string;
  userId: string;
  /** 邮箱是否已验证；注册后 false 需先去验证 */
  verified: boolean;
}

export interface QuestionView {
  runId: number;
  questionId: number; // 关联 question，让前端能拉该题对话线展示历史问答
  stem: string;
  probeType: string;
  responseFormat: string;
}

/** 先教后考：知识点拆解出的子知识点清单（POST /drill/{conceptId}/outline） */
export interface OutlineView {
  conceptId: number;
  name: string;
  topic: string;
  subPoints: string[];
  /** 已完成评分的子知识点；完成标记按子点展示，不代表整个大知识点完成。 */
  completedSubPoints: string[];
  cached: boolean;
}

/** 讲解页答疑消息（当前用户私有）：user = 学生提问（anchor 为选中的讲解片段），assistant = AI 回答。 */
export interface LessonQaMessageView {
  id: number;
  role: 'user' | 'assistant';
  text: string;
  anchor: string | null;
  createdAt: string;
}

/** 题目生成 SSE 首帧：题目元数据（不含 stem，stem 由后续 token 帧逐字推送） */
export interface QuestionMeta {
  runId: number;
  questionId: number;
  probeType: string;
  responseFormat: string;
}

export type Verdict = 'HIT' | 'PARTIAL' | 'MISS' | 'NA'; // NA = 未考察（没被实际问到的追问）

export interface ByConceptPoint {
  point: string;
  verdict: Verdict;
  evidence: string;
}

export interface ByConcept {
  conceptId: number;
  role: 'PRIMARY' | 'ANCHOR';
  pointResults: ByConceptPoint[];
  extraCorrect: string[];
  factualErrors: string[];
}

export interface GradeView {
  runId: number;
  questionId: number;
  rawScore: number;
  grade: string; // EASY / GOOD / HARD / MISSING
  byConceptJson: string; // JSON 字符串 -> 解析为 ByConcept[]
  transferExhausted?: boolean; // 补救测试判分后是否已达轮数上限（无需再考）
}

export interface RehearsalView {
  runId: number;
  round: number;
  maxRound: number;
  stem: string;
  finished: boolean;
  score: number | null;
  grade: string | null;
  allPassed: boolean | null;
  roundScores: string[];
  byConceptJson: string | null;
}

export interface NoteRequest {
  myWords: string;
  gapFound: string;
  nextAction: string;
}

export interface NoteView {
  runId: number;
  noteId: number;
  overlapRatio: number;
  debtLeft: number;
}

export interface DebtView {
  runId: number;
  stem: string;
  rawScore: number;
  answeredAt: string;
  weakPoints: string[]; // 判分里没打中的评分点（薄弱点清单）
  conceptId: number | null; // 主概念 id，供「判断自测」出新题
  planId: number | null; // 所属学习方向（按方向分组）
}

export interface LearningNextView {
  planId: number;
  planTitle: string;
  stepType: 'SUB_POINT' | 'CONCEPT_ASSESSMENT' | 'LEVEL_ASSESSMENT' | 'COMPLETE';
  layer: number;
  conceptId: number | null;
  conceptName: string | null;
  subPoint: string | null;
  subPointIndex: number;
  subPointTotal: number;
  assessmentDone: number;
  assessmentRequired: number;
  message: string;
}

/** 今日任务：复习 / 新学，含预生成的题目信息（READY 后 stem 非空） */
export interface DailyTaskView {
  id: number;
  planId: number | null;
  planTitle: string;
  kind: 'REVIEW' | 'NEW';
  conceptId: number;
  conceptName: string;
  layer: number;
  status: 'PENDING' | 'READY' | 'DONE' | 'SKIPPED';
  questionId: number | null;
  stem: string | null;
  probeType: string | null;
  /** 复习任务聚焦的子知识点（null = 概念级复习） */
  subPoint: string | null;
}

/** 随手记：用户手动沉淀的 Markdown 笔记，可挂到知识点/对话 */
export interface CasualNote {
  id: number;
  userId: number;
  title: string;
  content: string;
  conceptId: number | null;
  conceptName?: string | null;
  chatId: number | null;
  createdAt: string;
}


export interface ConceptProfile {
  conceptId: number;
  name: string;
  layer: number;
  masteryLevel: number;
}

export interface TopicProfile {
  topic: string;
  masteredLayer: number;
  concepts: ConceptProfile[];
}

export interface KnowledgePointsView {
  indexed: boolean;
  points: { name: string; chunkCount: number; snippets: string[] }[];
}

export interface ConceptValidationPoint {
  name: string;
  status: 'FOUND' | 'NOT_FOUND' | 'FAILED';
  message: string;
  evidence: string | null;
}

export interface ConceptValidationResponse {
  points: ConceptValidationPoint[];
}

// ============ 学习方向（痛点1：对话生成的学习规划） ============
export interface PlanChatMessage {
  role: 'user' | 'assistant';
  content: string;
}

export interface PlanPoint {
  name: string;
  layer: number;
  note: string;
}

export interface StudyPlanDraft {
  title: string;
  goal: string;
  points: PlanPoint[];
  corpusId?: number;
}

export interface IntakeResponse {
  reply: string;
  draft: StudyPlanDraft | null;
}

export interface PlanConceptView {
  id: number;
  name: string;
  topic: string;
  layer: number;
  masteryLevel: number;
  note: string | null; // 一句话提示（可编辑）
  /** 已生成的子知识点；尚未进入“先教后考”的知识点可能为空。 */
  subPoints: string[];
  /** 得分达到及格线（当前 60 分）的子知识点。 */
  completedSubPoints: string[];
}

/** 学习计划编辑请求体（方向或知识点通用字段） */
export interface PlanConceptWrite {
  name?: string;
  layer?: number;
  note?: string | null;
}
export interface PlanWrite {
  title?: string;
  goal?: string | null;
}

export interface PlanView {
  id: number;
  title: string;
  goal: string;
  concepts: PlanConceptView[];
  masteredCount: number;
  totalCount: number;
  dueReviewCount: number;
  corpusName?: string | null;
}

// 个人资料（用户上传的书 / 项目文档）
export interface CorpusView {
  id: number;
  name: string;
  charCount: number;
  sourceType?: 'UPLOAD' | 'LOCAL_FILE' | 'LOCAL_FOLDER' | string;
  createdAt?: string | null;
}

// ============ 问答记录（按题聚合的对话线） ============
export interface RunSummaryView {
  runId: number; // 该题最近一次 run 的 runId
  stem: string;
  rawScore: number;
  grade: string | null;
  answeredAt: string; // Instant -> ISO 字符串
  hasNote: boolean;
  questionId: number; // 对话线聚合键
  runCount: number; // 该题练过几轮（含重答与追问场）
  status: string; // GRADED / ANSWERING / READY
  planId: number | null; // 所属学习方向（按方向过滤问答记录）
}

export interface ConversationTurn {
  round: number;
  stem: string;
  rawAnswer: string | null;
  rawScore: number;
  passed: boolean | null;
  byConceptJson: string | null; // 解析为 ByConcept[]
  tutorText: string | null; // 教学讲解：LLM 老师式讲解，帮用户理解这道题
  images?: string[] | null; // 本轮用户消息附带的图片（data URL）
}

export interface ConversationRun {
  runId: number;
  mode: string; // LEARN / REHEARSAL
  status: string; // GRADED
  sourceRunId: number | null; // REHEARSAL 追问场指向来源 LEARN run
  rawScore: number;
  grade: string | null;
  answeredAt: string;
  turns: ConversationTurn[];
}

export interface ConversationView {
  questionId: number;
  stem: string;
  probeType: string;
  responseFormat: string;
  runs: ConversationRun[];
}

export interface RunDetailView {
  runId: number;
  questionId: number;
  stem: string;
  probeType: string;
  responseFormat: string;
  rawAnswer: string | null;
  rawScore: number;
  grade: string | null;
  byConceptJson: string | null; // 解析为 ByConcept[]
  answeredAt: string;
  hasNote: boolean;
  conceptIds: number[]; // 关联概念（锚点在 [0]），供"继续追问"接力 REHEARSAL
}

/** AI 复盘报告：题目 + 分数 + 薄弱点 + 对话总结（欠缺）+ 解题思路 + 记忆口诀 + 已保存的自省笔记 */
export interface ReviewView {
  runId: number;
  stem: string;
  rawScore: number;
  weakPoints: string[];
  gapSummary: string | null;
  approach: string | null;
  mnemonic: string | null;
  myWords: string | null; // 已保存的自省笔记（Markdown）；无则 null
  gapFound: string | null;
  nextAction: string | null;
}

/** 新增日常对话，并概括涉及到的知识点同步到计划和复习中*/
export interface KnowledgeCard {
    id: number;
    userId: number;
    source: string;
    question: string;
    answer: string | null;
    /** AI 当时回复的完整内容（Markdown 原文）；answer 是提炼摘要，detail 是完整记录 */
    detail: string | null;
    tags: string[];
    conceptId: number | null;
    planId: number | null;
    dueAt: string | null;
    reviewCount: number;
    createdAt: string;
}

