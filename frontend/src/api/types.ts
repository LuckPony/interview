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

/** 题目生成 SSE 首帧：题目元数据（不含 stem，stem 由后续 token 帧逐字推送） */
export interface QuestionMeta {
  runId: number;
  questionId: number;
  probeType: string;
  responseFormat: string;
}

export type Verdict = 'HIT' | 'PARTIAL' | 'MISS';

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

/** AI 复盘报告：题目 + 分数 + 薄弱点 + 对话总结（欠缺）+ 解题思路 + 记忆口诀 */
export interface ReviewView {
  runId: number;
  stem: string;
  rawScore: number;
  weakPoints: string[];
  gapSummary: string | null;
  approach: string | null;
  mnemonic: string | null;
}
