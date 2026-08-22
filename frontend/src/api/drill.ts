import { apiFetch, getToken, getLlmKeyHeader } from './client';
import type {
  QuestionView,
  QuestionMeta,
  GradeView,
  RehearsalView,
  NoteRequest,
  NoteView,
  DebtView,
  TopicProfile,
  PlanChatMessage,
  IntakeResponse,
  StudyPlanDraft,
  PlanView,
  PlanConceptWrite,
  PlanWrite,
  CorpusView,
  RunSummaryView,
  RunDetailView,
  ConversationView,
  DailyTaskView,
  ReviewView,
  ConceptValidationResponse,
  LearningNextView,
  KnowledgePointsView,
  OutlineView,
} from './types';

export interface Timing {
  timing: string; // ON / OFF
  activeSeconds: number;
}

// SSE 流解析：EventSource 带不了 Authorization header，且 URL 是相对路径拼不到 API_BASE，
// 所以改用 fetch + ReadableStream 手写 SSE 解析（能带 Bearer + 绝对地址）。
// 同一套解析同时服务于「讲解流（GET tutor-stream）」与「提交即讲解流（POST submit）」。

export interface TutorStream {
  cancel: () => void;
}

/** SSE 事件回调：meta 仅出题端点下发（题目元数据）；grade 仅 submit 端点下发（判分面板）；
 *  result 仅 rehearsal/answer 下发（下一轮/结算）；
 *  message/data 为讲解/题目 token；done 标记结束 */
interface SseHandlers {
  onMeta?: (meta: QuestionMeta) => void;
  onGrade?: (g: GradeView) => void;
  onResult?: (v: RehearsalView) => void;
  onDraft?: (draft: StudyPlanDraft | null) => void;
  onReasoning?: (text: string) => void;
  onReveal?: () => void; // event: reveal —— 答案已揭示（评分只取揭示之前的回答）
  onToken: (token: string) => void;
  onDone: (fullText?: string) => void;
  onDoneView?: (v: QuestionView) => void; // 流式出题：done 事件带 QuestionView
  onError: (status?: number, message?: string) => void;
}

const API_BASE_SSE: string = (import.meta.env.VITE_API_BASE ?? '').replace(/\/$/, '');

/**
 * 打开一条 SSE：fetch 后逐行解析 SSE，按事件类型分派回调。
 * - event: grade   → onGrade（仅 POST /submit 下发，含完整 GradeView）
 * - 默认 message / 纯 data → onToken（讲解 token）
 * - event: done    → onDone（fullText 兜底覆盖，修复偶发末尾截断）
 * - 非 2xx / 流式中止 → onError（带 status+message，前端据此区分 409 闸门等）
 */
function openSse(url: string, init: RequestInit, handlers: SseHandlers): TutorStream {
  const controller = new AbortController();
  let cancelled = false;
  (async () => {
    try {
      // 桌面端本机 LLM key：随 SSE 请求临时带给后端（Authorization 已由调用方带上）
      const llmKey = await getLlmKeyHeader();
      const headers: Record<string, string> = {
        ...(init.headers as Record<string, string> | undefined),
        ...(llmKey ? { 'X-LLM-Key': llmKey } : {}),
      };
      const res = await fetch(url, { ...init, headers, signal: controller.signal });
      if (!res.ok || !res.body) {
        let msg = '';
        try { msg = await res.text(); } catch { /* ignore */ }
        handlers.onError(res.status, msg);
        return;
      }
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buf = '';
      let currentEvent: string | null = null;
      const currentData: string[] = [];
      let finished = false;

      const dispatchEvent = () => {
        const payload = currentData.join('\n');
        if (currentEvent === 'meta') {
          if (handlers.onMeta) {
            try { handlers.onMeta(JSON.parse(payload) as QuestionMeta); } catch { /* ignore */ }
          }
        } else if (currentEvent === 'grade') {
          if (handlers.onGrade) {
            try { handlers.onGrade(JSON.parse(payload) as GradeView); } catch { /* ignore */ }
          }
        } else if (currentEvent === 'result') {
          if (handlers.onResult) {
            try { handlers.onResult(JSON.parse(payload) as RehearsalView); } catch { /* ignore */ }
          }
        } else if (currentEvent === 'draft') {
          if (handlers.onDraft) {
            try { handlers.onDraft(JSON.parse(payload) as StudyPlanDraft | null); } catch { handlers.onDraft(null); }
          }
        } else if (currentEvent === 'reasoning') {
          if (handlers.onReasoning) {
            try {
              const parsed = JSON.parse(payload) as { text?: string };
              if (typeof parsed.text === 'string') handlers.onReasoning(parsed.text);
            } catch {
              if (payload) handlers.onReasoning(payload);
            }
          }
        } else if (currentEvent === 'reveal') {
          if (handlers.onReveal) handlers.onReveal();
        } else if (currentEvent === 'error') {
          if (handlers.onError) {
            try {
              const parsed = JSON.parse(payload) as { message?: string };
              handlers.onError(undefined, parsed.message);
            } catch {
              handlers.onError(undefined, payload);
            }
          }
        } else if (currentEvent === 'done') {
          finished = true;
          let fullText: string | undefined;
          if (payload && payload !== '[DONE]') {
            try {
              const parsed = JSON.parse(payload);
              if (typeof parsed.text === 'string') fullText = parsed.text;
              if (parsed && typeof parsed.runId === 'number' && handlers.onDoneView) {
                handlers.onDoneView(parsed as QuestionView);
              }
            } catch { /* 非 JSON 兜底 */ }
          }
          handlers.onDone(fullText);
          reader.cancel().catch(() => {});
        } else if (currentEvent == null || currentEvent === 'message') {
          if (payload && payload !== '[DONE]') {
            try {
              const parsed = JSON.parse(payload);
              if (typeof parsed.text === 'string') handlers.onToken(parsed.text);
            } catch {
              // 解析失败的裸 token：若形如 {"text":...} 的原始帧（说明帧本身坏了），
              // 不要把它当文本注入气泡，避免代码块里出现 {"text":"..."} 乱码。
              if (!payload.startsWith('{')) handlers.onToken(payload);
            }
          }
        }
        currentEvent = null;
        currentData.length = 0;
      };

      const handleLine = (line: string) => {
        if (line === '') dispatchEvent();
        else if (line.startsWith('event:')) currentEvent = line.slice(6).trim();
        else if (line.startsWith('data:')) currentData.push(line.slice(5).trim());
      };

      const processBuf = async () => {
        let idx: number;
        while ((idx = buf.indexOf('\n')) >= 0) {
          const line = buf.slice(0, idx).trim();
          buf = buf.slice(idx + 1);
          handleLine(line);
          if (finished || cancelled) return;
          await new Promise((r) => setTimeout(r, 0)); // 让出事件循环，确保每 token 独立渲染
        }
      };

      for (;;) {
        const { done, value } = await reader.read();
        if (done || cancelled) {
          if (!finished && !cancelled) {
            buf += decoder.decode();
            await processBuf();
            if (!finished && !cancelled) {
              const tail = buf.trim();
              if (tail) handleLine(tail);
              if (!finished) dispatchEvent();
            }
          }
          break;
        }
        buf += decoder.decode(value, { stream: true });
        await processBuf();
        if (finished || cancelled) return;
      }
    } catch {
      if (!cancelled) handlers.onError();
    }
  })();
  return {
    cancel: () => {
      cancelled = true;
      controller.abort();
    },
  };
}

/**
 * 模拟面试作答即讲解流（POST /rehearsal/{runId}/answer，SSE）：先下发 event:result
 * （下一轮/结算，RehearsalView），再逐 token 推讲解，最后 event:done。合并了旧的两段式
 *（rehearsalAnswer 同步 JSON + 另开 tutor-stream 拉讲解），消除双重 LLM 往返。
 * onDone 接收后端 done 事件带回的完整 text（截断兜底），与 submitStream 一致。
 */
export function rehearsalAnswerStream(
  runId: number,
  rawAnswer: string,
  onResult: (v: RehearsalView) => void,
  onToken: (token: string) => void,
  onDone: (fullText?: string) => void,
  onError: (status?: number, message?: string) => void,
): TutorStream {
  const token = getToken();
  const url = `${API_BASE_SSE}/api/drill/rehearsal/${runId}/answer`;
  return openSse(url, {
    method: 'POST',
    headers: {
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ rawAnswer }),
  }, { onResult, onToken, onDone, onError });
}

/**
 * 提交即讲解流（POST /{runId}/submit，SSE）：先下发 event:grade（判分面板），
 * 再逐 token 推讲解，最后 event:done 推完整文本。合并了旧的两段式（submit + tutor-stream）。
 */
export function submitStream(
  runId: number,
  rawAnswer: string,
  timing: Timing | undefined,
  onGrade: (g: GradeView) => void,
  onToken: (token: string) => void,
  onDone: (fullText?: string) => void,
  onError: (status?: number, message?: string) => void,
): TutorStream {
  const token = getToken();
  const url = `${API_BASE_SSE}/api/drill/${runId}/submit`;
  return openSse(url, {
    method: 'POST',
    headers: {
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      rawAnswer,
      timing: timing?.timing,
      activeSeconds: timing?.activeSeconds,
    }),
  }, { onGrade, onToken, onDone, onError });
}

/**
 * 题目生成 SSE 流（POST /next | /start | /start-plan，SSE）：
 * 先下发 event:meta（题目元数据，不含 stem），再逐 token 推 stem 文本，最后 event:done。
 * 用户进入聊天页后先看到「正在生成题目…」→ 题干逐字浮现 → 完成后开放输入区。
 */
export function startStreamSse(
  path: string,
  body: unknown,
  onMeta: (meta: QuestionMeta) => void,
  onToken: (token: string) => void,
  onDone: () => void,
  onError: (status?: number, message?: string) => void,
): TutorStream {
  const token = getToken();
  const url = `${API_BASE_SSE}${path}`;
  return openSse(url, {
    method: 'POST',
    headers: {
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(body),
  }, { onMeta, onToken, onDone: () => onDone(), onError });
}

/**
 * 对话式作答 SSE 流（POST /{runId}/chat，SSE）：
 * 用户发送答案后，显示「思考中…」→ 逐 token 推 AI 回复 → event:done。
 * 不判分——评分延迟到用户点「结束并评分」时由 finish 端点一次性完成。
 *
 * @param reveal 用户显式点击「看答案」→ true：服务端记录答案揭示边界并让 AI 给出完整答案，
 *               评分只取揭示之前的用户回答；同时前端收到 event:reveal 在聊天线程渲染分隔线。
 * @param onReveal 答案已揭示回调（event:reveal），用于标记该轮 AI 回复为「参考答案」
 */
export function chatStream(
  runId: number,
  rawAnswer: string,
  reveal: boolean,
  onToken: (token: string) => void,
  onReasoning: (text: string) => void,
  onDone: () => void,
  onError: (status?: number, message?: string) => void,
  onReveal?: () => void,
  onGrade?: (grade: GradeView) => void,
): TutorStream {
  const token = getToken();
  const url = `${API_BASE_SSE}/api/drill/${runId}/chat`;
  return openSse(url, {
    method: 'POST',
    headers: {
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ rawAnswer, reveal }),
  }, { onToken, onReasoning, onDone: () => onDone(), onError, onReveal, onGrade });
}

/**
 * 子知识点讲解 SSE 流（POST /{conceptId}/lesson?subPoint=...，SSE）：
 * 逐 token 推讲解文本，event:done 结束。缓存命中时后端整体一帧下发。
 */
export function lessonStream(
  conceptId: number,
  subPoint: string,
  onToken: (token: string) => void,
  onReasoning: (text: string) => void,
  onDone: () => void,
  onError: (status?: number, message?: string) => void,
): TutorStream {
  const token = getToken();
  const url = `${API_BASE_SSE}/api/drill/${conceptId}/lesson?subPoint=${encodeURIComponent(subPoint)}`;
  return openSse(url, {
    method: 'POST',
    headers: {
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      'Content-Type': 'application/json',
    },
  }, { onToken, onReasoning, onDone: () => onDone(), onError });
}

export const drill = {
  next: () => apiFetch<QuestionView>('/drill/next', { method: 'POST' }),

  // 用户自选概念开练（痛点1：用户掌 what，服务端 pickFor 定 task）；subPoint 用于「先教后考」限定出题到子知识点
  start: (conceptId: number, subPoint?: string) =>
    apiFetch<QuestionView>('/drill/start', {
      method: 'POST',
      body: JSON.stringify({ conceptId, subPoint }),
    }),

  // 先教后考：拆解知识点为子知识点清单（缓存 concept.lesson_outline）
  outline: (conceptId: number) =>
    apiFetch<OutlineView>(`/drill/${conceptId}/outline`, { method: 'POST' }),

  learningNext: (planId: number) =>
    apiFetch<LearningNextView>(`/drill/learning-next/${planId}`),

  // 方向级入口：综合检测仍复用 QuestionView + 聊天 + finish
  startPlan: (
    planId: number,
    mode: 'continue' | 'review' | 'layer' | 'concept-assessment' | 'level-assessment' = 'continue',
    layer?: number,
    conceptId?: number,
  ) =>
    apiFetch<QuestionView>('/drill/start-plan', {
      method: 'POST',
      body: JSON.stringify({ planId, mode, layer, conceptId }),
    }),

  // —— 流式出题：思考 + 题干逐字推送（不用干等） ——
  startStream: (
    conceptId: number,
    onReasoning: (t: string) => void,
    onStem: (t: string) => void,
    onDone: (v: QuestionView) => void,
    onError: (status?: number, message?: string) => void,
  ): TutorStream => {
    const token = getToken();
    return openSse(`${API_BASE_SSE}/api/drill/start/stream`, {
      method: 'POST',
      headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), 'Content-Type': 'application/json' },
      body: JSON.stringify({ conceptId }),
    }, { onReasoning, onToken: onStem, onDoneView: onDone, onDone: () => {}, onError });
  },

  startPlanStream: (
    planId: number,
    mode: string,
    layer: number | undefined,
    onReasoning: (t: string) => void,
    onStem: (t: string) => void,
    onDone: (v: QuestionView) => void,
    onError: (status?: number, message?: string) => void,
  ): TutorStream => {
    const token = getToken();
    return openSse(`${API_BASE_SSE}/api/drill/start-plan/stream`, {
      method: 'POST',
      headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), 'Content-Type': 'application/json' },
      body: JSON.stringify({ planId, mode, layer }),
    }, { onReasoning, onToken: onStem, onDoneView: onDone, onDone: () => {}, onError });
  },

  nextStream: (
    onReasoning: (t: string) => void,
    onStem: (t: string) => void,
    onDone: (v: QuestionView) => void,
    onError: (status?: number, message?: string) => void,
  ): TutorStream => {
    const token = getToken();
    return openSse(`${API_BASE_SSE}/api/drill/next/stream`, {
      method: 'POST',
      headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), 'Content-Type': 'application/json' },
      body: JSON.stringify({}),
    }, { onReasoning, onToken: onStem, onDoneView: onDone, onDone: () => {}, onError });
  },

  // 历史记录「继续练习」：复用原题（同 questionId）开一条新 run 重新作答
  restart: (runId: number) =>
    apiFetch<QuestionView>('/drill/restart', {
      method: 'POST',
      body: JSON.stringify({ runId }),
    }),

  rehearsalStart: (conceptId?: number) =>
    apiFetch<RehearsalView>('/drill/rehearsal/start', {
      method: 'POST',
      body: conceptId != null ? JSON.stringify({ conceptId }) : undefined,
    }),


  /** 从学习计划启动模拟面试 */
  rehearsalStartFromPlan: (planId: number) =>
      apiFetch<RehearsalView>('/drill/rehearsal/start-plan', {
        method: 'POST',
        body: JSON.stringify({ planId }),
      }),

  // LEARN grade 卡"继续追问"：spawn 一条 mode=REHEARSAL 的追问场，复用 source questionId
  followup: (runId: number) =>
    apiFetch<RehearsalView>(`/drill/${runId}/followup`, { method: 'POST' }),

  // 主动结束追问 / 模拟面试：强制 settle
  rehearsalEnd: (runId: number) =>
    apiFetch<RehearsalView>(`/drill/rehearsal/${runId}/end`, { method: 'POST' }),

  // 结束对话并评分：基于整轮对话一次性评分（不再逐答即判）
  finish: (runId: number) =>
    apiFetch<GradeView>(`/drill/${runId}/finish`, { method: 'POST' }),

  note: (runId: number, req: NoteRequest) =>
    apiFetch<NoteView>(`/drill/${runId}/note`, { method: 'POST', body: JSON.stringify(req) }),

  debt: () => apiFetch<DebtView[]>('/drill/debt'),

  profile: () => apiFetch<TopicProfile[]>('/drill/profile'),

  // 问答记录：按题聚合的对话线列表 + 单题完整对话线
  history: () => apiFetch<RunSummaryView[]>('/drill/history'),

  conversation: (questionId: number) =>
    apiFetch<ConversationView>(`/drill/history/conversation/${questionId}`),

  // 删除一道题的整条问答记录（级联：追问场/判分/复盘/笔记），删除前需二次确认
  deleteConversation: (questionId: number) =>
    apiFetch<{ ok: boolean; deleted: number }>(`/drill/history/conversation/${questionId}`, { method: 'DELETE' }),

  // 删除单条作答记录（内化复盘页删除欠账/复盘数据），删除前需二次确认
  deleteRun: (runId: number) =>
    apiFetch<{ ok: boolean; deleted: number }>(`/drill/runs/${runId}`, { method: 'DELETE' }),

  getRun: (runId: number) => apiFetch<RunDetailView>(`/drill/${runId}`),

  // AI 复盘：题目 + 对话总结（欠缺）+ 解题思路 + 记忆口诀（按 runId 缓存）
  review: (runId: number) => apiFetch<ReviewView>(`/drill/${runId}/review`),

  // 今日任务：每日自动排期（复习 + 新学），预生成题秒开
  today: () => apiFetch<DailyTaskView[]>('/drill/today'),

  startTask: (taskId: number) =>
    apiFetch<QuestionView>(`/drill/task/${taskId}/start`, { method: 'POST' }),

  completeTask: (taskId: number) =>
    apiFetch<{ ok: boolean }>(`/drill/task/${taskId}/done`, { method: 'POST' }),

  nextTask: () => apiFetch<QuestionView>('/drill/next-task', { method: 'POST' }),
};

// 学习方向（痛点1）：对话生成的学习规划
export const studyPlan = {
  list: () => apiFetch<PlanView[]>('/study-plan'),

  // corpusId 可选：用户先上传资料，再基于它对话规划
  intake: (messages: PlanChatMessage[], corpusId?: number) =>
    apiFetch<IntakeResponse>('/study-plan/intake', {
      method: 'POST',
      body: JSON.stringify({ messages, corpusId }),
    }),

  // 流式 intake：逐 token 推对话回复，event:draft 推草稿，event:done 结束
  intakeStream: (
    messages: PlanChatMessage[],
    corpusId: number | undefined,
    onToken: (token: string) => void,
    onDraft: (draft: StudyPlanDraft | null) => void,
    onDone: () => void,
    onError: (status?: number, message?: string) => void,
  ): TutorStream => {
    const token = getToken();
    const url = `${API_BASE_SSE}/api/study-plan/intake/stream`;
    return openSse(url, {
      method: 'POST',
      headers: {
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ messages, corpusId }),
    }, { onToken, onDraft, onDone: () => onDone(), onError });
  },

  validateCandidates: (draft: StudyPlanDraft) =>
    apiFetch<ConceptValidationResponse>('/study-plan/validate-candidates', {
      method: 'POST',
      body: JSON.stringify({ draft }),
    }),

  confirm: (draft: StudyPlanDraft) =>
    apiFetch<PlanView>('/study-plan/confirm', {
      method: 'POST',
      body: JSON.stringify({ draft }),
    }),

  // AI 补充知识点：一句话让 AI 在现有方向下追加知识点（按名去重合并）
  aiRevise: (planId: number, instruction: string) =>
    apiFetch<PlanView>(`/study-plan/${planId}/ai-revise`, {
      method: 'POST',
      body: JSON.stringify({ instruction }),
    }),

  // —— 用户手动编辑（自主权）：改方向 / 删方向 / 增改删知识点 ——
  update: (id: number, req: PlanWrite) =>
    apiFetch<PlanView>(`/study-plan/${id}`, {
      method: 'PUT',
      body: JSON.stringify(req),
    }),

  remove: (id: number) =>
    apiFetch<{ ok: boolean }>(`/study-plan/${id}`, { method: 'DELETE' }),

  addConcept: (planId: number, req: PlanConceptWrite) =>
    apiFetch<PlanView>(`/study-plan/${planId}/concepts`, {
      method: 'POST',
      body: JSON.stringify(req),
    }),

  updateConcept: (conceptId: number, req: PlanConceptWrite) =>
    apiFetch<PlanView>(`/study-plan/concepts/${conceptId}`, {
      method: 'PUT',
      body: JSON.stringify(req),
    }),

  removeConcept: (conceptId: number) =>
    apiFetch<{ ok: boolean }>(`/study-plan/concepts/${conceptId}`, { method: 'DELETE' }),
};

// AI 设置：模型 / api-key / base-url（设置页，改完立即生效）
// 注意：后端【不回显 key 的任何片段】，只告知是否已配置（hasApiKey）
export interface AiSettingsView {
  provider: string;
  baseUrl: string;
  model: string;
  hasApiKey: boolean;
  temperature: number;
}
export const aiSettings = {
  get: () => apiFetch<AiSettingsView>('/settings/ai'),
  update: (cfg: { provider: string; baseUrl: string; apiKey: string; model: string; temperature: number }) =>
    apiFetch<{ ok: boolean }>('/settings/ai', {
      method: 'POST',
      body: JSON.stringify(cfg),
    }),
};

// 个人资料：上传书 / 项目文档，解析文本后供规划与出题引用
export const corpus = {
  // 网页态：浏览器上传字节
  upload: (file: File) => {
    const form = new FormData();
    form.append('file', file);
    return apiFetch<CorpusView>('/corpus/upload', { method: 'POST', body: form });
  },
  // 桌面态：直接把本地路径交给后端读盘，免上传（解大项目痛点；仅本地部署有意义）
  fromPath: (path: string) =>
    apiFetch<CorpusView>('/corpus/from-path', {
      method: 'POST',
      body: JSON.stringify({ path }),
    }),
  // 云端桌面态：Electron 在本机读好的文件字节传上来，服务端 Tika 解析合并
  fromFiles: (form: FormData) =>
    apiFetch<CorpusView>('/corpus/from-files', { method: 'POST', body: form }),
  // 资料候选知识点（异步拆块+LLM 标注完成后返回；indexed=false 表示还在处理）
  knowledgePoints: (corpusId: number) =>
    apiFetch<KnowledgePointsView>(`/corpus/${corpusId}/knowledge-points`),
};
