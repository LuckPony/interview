import { useCallback, useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { Timer, NotebookPen, Compass, ChevronRight, ArrowLeft, RefreshCw, Target, Plus, X, ImagePlus, Code2 } from 'lucide-react';
import { drill, aiSettings, chatStream, lessonStream, studyPlan, type TutorStream } from '../api/drill';
import { Button, Tag } from '../components/ui';
import { NoteDialog } from '../components/NoteDialog';
import { ApiError } from '../api/client';
import { PROBE_LABEL } from '../lib/labels';
import type { QuestionView, QuestionMeta, GradeView, PlanView, ConversationView } from '../api/types';
import { ConversationStream, VerdictPanel } from '../components/ConversationStream';
import { Markdown } from '../components/Markdown';
import { Plans } from './Plans';
import './Drill.css';

function fmt(sec: number): string {
  const m = Math.floor(sec / 60);
  const s = sec % 60;
  return `${m}:${s.toString().padStart(2, '0')}`;
}

// —— 聊天消息：stem(题干) / chat(对话) 两型；reasoning 为 AI 思考过程（可折叠展示）——
interface ChatMsg {
  id: string;
  role: 'ai' | 'me';
  text: string;
  streaming?: boolean;
  type: 'stem' | 'chat';
  reasoning?: string;
  paused?: boolean;   // AI 回复被用户「暂停」：已显示的内容保留，但未答完的回复不落库
  images?: string[];  // 用户消息附带的图片（data URL）
  revealed?: boolean; // 该 AI 回复是「参考答案」（答案已揭示，评分只取揭示之前的回答）
}

// —— 一次练习会话的上下文：决定「下一题」去哪抽 ——
type SessionCtx =
  | { kind: 'free' }
  | { kind: 'concept'; conceptId: number }
  | { kind: 'plan'; planId: number; mode: 'continue' | 'review' | 'layer' | 'layer-practice'; layer?: number }
  | { kind: 'workflow'; planId: number }
  | { kind: 'assessment'; planId: number; mode: 'concept-assessment' | 'level-assessment'; layer: number; conceptId?: number }
  | { kind: 'task' } // 今日任务：做完自动接下一道预生成题
  | { kind: 'scoped'; planId?: number; scope: 'concept' | 'layer'; conceptId?: number; layer?: number } // 整知识点 / 整层级练习
  | { kind: 'teach'; conceptId: number; subIndex: number; planId?: number; taskId?: number }; // 先教后考：做完当前子点 → 下一个子点/综合检测

// —— learn 阶段：生成题目 → 对话 → 评分中 → 已评分 ——
type Phase = 'generating' | 'chatting' | 'finishing' | 'graded';

let msgCounter = 0;
const nextMsgId = () => `m${++msgCounter}`;

// 先教后考开关（localStorage，默认开）
const TEACH_FIRST_KEY = 'mianba.teachFirst';

// 把一条对话线（全部 run 的所有轮）扁平化为聊天消息数组：
// AI 题干 → 每轮「我的回答 / AI 讲解」按时间顺序串起来。恢复对话与追问场共用。
function convToMessages(conv: ConversationView): ChatMsg[] {
  const msgs: ChatMsg[] = [
    { id: nextMsgId(), role: 'ai', text: conv.stem, streaming: false, type: 'stem' },
  ];
  for (const run of conv.runs) {
    for (const turn of run.turns) {
      if (turn.rawAnswer) msgs.push({ id: nextMsgId(), role: 'me', text: turn.rawAnswer, type: 'chat', images: turn.images ?? [] });
      if (turn.tutorText) msgs.push({ id: nextMsgId(), role: 'ai', text: turn.tutorText, type: 'chat' });
    }
  }
  return msgs;
}

// 只取某 run 已有的轮次（不含题干）：「继续学习」恢复到进行中的题时，把之前的问答历史载入聊天线程
function runTurnsToMessages(conv: ConversationView, runId: number): ChatMsg[] {
  const run = conv.runs.find((r) => r.runId === runId);
  if (!run) return [];
  const msgs: ChatMsg[] = [];
  for (const turn of run.turns) {
    if (turn.rawAnswer) msgs.push({ id: nextMsgId(), role: 'me', text: turn.rawAnswer, type: 'chat', images: turn.images ?? [] });
    if (turn.tutorText) msgs.push({ id: nextMsgId(), role: 'ai', text: turn.tutorText, type: 'chat' });
  }
  return msgs;
}

export function Drill() {
  const location = useLocation();
  const navigate = useNavigate();
  const restoreRef = useRef<string | null>(null);
  // IME 组合态锁：拼音选词 / 输入法激活期间的回车一律放行（不提交）
  const composingRef = useRef(false);
  // 出题去重锁：防止连续点击时重复打后端生成题
  const genLockRef = useRef(false);
  // 当前活跃 SSE 流引用（卸载 / 换题时 cancel）
  const sseRef = useRef<TutorStream | null>(null);
  // 题干打字机定时器（卸载 / 换题时清理）
  const typewriterRef = useRef<ReturnType<typeof setInterval> | null>(null);
  // 聊天面板底部哨兵：整页滚动时，新消息 / 评分出现后把最新内容与输入框滚进视野
  const endRef = useRef<HTMLDivElement>(null);

  // —— 视图状态机：home(选方向) / teach(先教后考) / learn(做题)。view 由路由派生（见上方）。
  const [plans, setPlans] = useState<PlanView[]>([]);
  const [planErr, setPlanErr] = useState('');

  // —— 浏览模式（History 卡片点进来）：仅展示该题对话历史 ——
  const [browseQid, setBrowseQid] = useState<number | null>(
    () => (location.state as { viewQuestionId?: number } | null)?.viewQuestionId ?? null,
  );
  const browseMode = browseQid != null;
  const [conv, setConv] = useState<ConversationView | null>(null);
  const [convLoading, setConvLoading] = useState(false);

  // —— learn 态：题目元数据 + 聊天消息 + 输入 + 阶段 ——
  const [ctx, setCtx] = useState<SessionCtx | null>(null);
  const [meta, setMeta] = useState<QuestionMeta | null>(null);
  const [messages, setMessages] = useState<ChatMsg[]>([]);
  const [input, setInput] = useState('');
  // 待发送的图片（data URL，压缩后）；当前模型支持视觉时才允许添加
  const [images, setImages] = useState<string[]>([]);
  const [vision, setVision] = useState<boolean | null>(null); // null=未知（未拉取设置）
  const fileRef = useRef<HTMLInputElement>(null);
  // 当前模型是否支持视觉：决定输入区是否显示图片上传入口
  useEffect(() => {
    let active = true;
    aiSettings.get().then((s) => { if (active) setVision(s.supportsVision); }).catch(() => { if (active) setVision(false); });
    return () => { active = false; };
  }, []);
  const [phase, setPhase] = useState<Phase>('generating');
  const [err, setErr] = useState('');
  const [gate, setGate] = useState('');
  const [timingOn, setTimingOn] = useState(false);
  const [seconds, setSeconds] = useState(0);

  // —— 评分结果（独立于聊天消息，一个对话只有一个评分）——
  const [grade, setGrade] = useState<GradeView | null>(null);

  // 当前对话是否挂在一条已判分(GRADED)的 run 上继续（历史记录「继续对话」= 用户向 AI 提问，
  // 不重新评分，故隐藏「结束并评分」）
  const [resumedGraded, setResumedGraded] = useState(false);

  // —— 内化笔记弹窗 ——
  const [noteRunId, setNoteRunId] = useState<number | null>(null);
  const [noteStem, setNoteStem] = useState('');

  // —— 先教后考：知识点拆解 + 子知识点讲解 ——
  type TeachState = {
    conceptId: number;
    name: string;
    topic: string;
    subPoints: string[];
    curIdx: number;   // -1 = 只在清单页（未选中具体子点）
    done: string[];   // 已做过题的子点名（本次会话内）
    planId?: number;  // 从统一工作流进入时保留，全部子点后接综合检测
    taskId?: number;  // 今日新学任务：子点达标后才消费，不在打开教学时提前完成
  };
  const [teach, setTeach] = useState<TeachState | null>(null);
  const [lessonText, setLessonText] = useState('');
  const [lessonReasoning, setLessonReasoning] = useState('');
  const [lessonBusy, setLessonBusy] = useState(false);
  const [outlineBusy, setOutlineBusy] = useState(false);
  const [teachFirst, setTeachFirst] = useState<boolean>(() => {
    try { return localStorage.getItem(TEACH_FIRST_KEY) !== '0'; } catch { return true; }
  });

  // —— 视图由路由派生：/drill=home，/drill/teach/:cid(/:subIdx)=先教后考，/drill/learn=做题 ——
  // 子路由让浏览器前进/后退与「返回」按钮直接走真实历史，不再用内部状态栈。
  const pathSegs = location.pathname.split('/').filter(Boolean); // ['drill', ...]
  const routeTeachCid = pathSegs[1] === 'teach' ? Number(pathSegs[2]) : undefined;
  const routeSubIdx = pathSegs[1] === 'teach' && pathSegs[3] != null ? Number(pathSegs[3]) : undefined;
  const view: 'home' | 'teach' | 'learn' =
    pathSegs[1] === 'teach' ? 'teach' : pathSegs[1] === 'learn' ? 'learn' : 'home';
  // teach 路由可携带工作流/任务上下文（?plan=&task=），进入做题时保持链式推进
  const planQ = Number(new URLSearchParams(location.search).get('plan')) || undefined;
  const taskQ = Number(new URLSearchParams(location.search).get('task')) || undefined;

  // 「返回」按钮：有历史就后退（鼠标后退键同效），直达场景回学习计划首页
  const backOrHome = () => {
    if (location.key === 'default') navigate('/drill');
    else navigate(-1);
  };

  // —— 离开 learn 视图时取消流式请求（聊天 SSE / 题干打字机），会话状态保留供返回展示 ——
  const prevViewRef = useRef(view);
  useEffect(() => {
    if (prevViewRef.current === 'learn' && view !== 'learn') {
      if (sseRef.current) { sseRef.current.cancel(); sseRef.current = null; }
      if (typewriterRef.current) { clearInterval(typewriterRef.current); typewriterRef.current = null; }
    }
    prevViewRef.current = view;
  }, [view]);

  // —— 已按路由加载完成的 teach 视图（cid + sub，-1=清单；用于防止重复播放讲解）——
  const teachRouteRef = useRef<{ cid: number; sub: number } | null>(null);

  // —— 路由 → teach 状态同步：进入/切换知识点拉 outline；切换子点播放讲解 ——
  useEffect(() => {
    if (view !== 'teach') { teachRouteRef.current = null; return; }
    const cid = routeTeachCid;
    if (cid == null || Number.isNaN(cid)) { navigate('/drill', { replace: true }); return; }
    const sub = routeSubIdx ?? -1;

    if (!teach || teach.conceptId !== cid) {
      // 首次进入或切换知识点：拉子知识点清单（懒生成并缓存于后端）
      teachRouteRef.current = { cid, sub };
      if (sseRef.current) { sseRef.current.cancel(); sseRef.current = null; }
      if (typewriterRef.current) { clearInterval(typewriterRef.current); typewriterRef.current = null; }
      setOutlineBusy(true);
      setErr('');
      setLessonText('');
      setLessonReasoning('');
      setLessonBusy(false);
      setTeach({ conceptId: cid, name: '', topic: '', subPoints: [], curIdx: sub, done: [], planId: planQ, taskId: taskQ });
      (async () => {
        try {
          const o = await drill.outline(cid);
          if (teachRouteRef.current?.cid !== cid) return; // 已切到别的知识点
          setTeach({
            conceptId: cid, name: o.name, topic: o.topic,
            subPoints: o.subPoints, curIdx: sub,
            done: o.completedSubPoints ?? [], planId: planQ, taskId: taskQ,
          });
        } catch (e) {
          if (teachRouteRef.current?.cid !== cid) return;
          setErr(e instanceof ApiError ? e.message : '拆解失败');
          setTeach(null);
          navigate('/drill', { replace: true });
        } finally {
          setOutlineBusy(false);
        }
      })();
      return;
    }

    // 同一知识点：只同步子点层级（前进/后退到清单或某个讲解页）
    const loaded = teachRouteRef.current;
    if (loaded && loaded.cid === cid && loaded.sub === sub) return; // 已处理，避免重复播放
    teachRouteRef.current = { cid, sub };
    if (sub < 0) {
      setTeach({ ...teach, curIdx: -1 });
    } else if (sub < teach.subPoints.length) {
      setTeach({ ...teach, curIdx: sub });
      playSubLesson(cid, teach.subPoints[sub]);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [view, routeTeachCid, routeSubIdx, planQ, taskQ]);

  // —— 加载学习方向列表 ——
  const loadPlans = useCallback(async () => {
    try {
      setPlans(await studyPlan.list());
    } catch (e) {
      setPlanErr(e instanceof ApiError ? e.message : '加载失败');
    }
  }, []);

  useEffect(() => {
    if (view === 'home') loadPlans();
  }, [view, loadPlans]);

  // —— 挂载即处于 /drill/learn 且无会话上下文（刷新/直达）：回学习计划首页重新进入 ——
  useEffect(() => {
    if (view === 'learn' && !meta && !genLockRef.current && !browseMode) {
      navigate('/drill', { replace: true });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // —— 新消息 / 评分出现时，把聊天面板底部（最新气泡 + 输入框）滚进视野 ——
  useEffect(() => {
    endRef.current?.scrollIntoView({ block: 'end' });
  }, [messages, grade]);

  // —— 卸载时关 SSE 流 + 清打字机 ——
  useEffect(() => () => {
    if (sseRef.current) sseRef.current.cancel();
    if (typewriterRef.current) clearInterval(typewriterRef.current);
  }, []);

  // —— 计时器 ——
  useEffect(() => {
    if (!timingOn || phase === 'graded' || phase === 'finishing') return;
    const t = setInterval(() => setSeconds((s) => s + 1), 1000);
    return () => clearInterval(t);
  }, [timingOn, phase]);

  // —— 导航状态统一入口 ——
  useEffect(() => {
    const st = location.state as {
      viewQuestionId?: number;
      planId?: number; planMode?: 'continue' | 'review' | 'layer' | 'layer-practice' | 'workflow'; layer?: number;
      conceptId?: number; taskId?: number; mode?: string; view?: string; openSubPoints?: boolean;
    } | null;
    if (!st) return;

    const navKey = st.planId != null ? `plan:${st.planId}:${st.planMode}`
      : st.conceptId != null ? `concept:${st.conceptId}`
      : st.taskId != null ? `task:${st.taskId}`
      : st.mode ?? '';
    if (navKey && restoreRef.current !== navKey) {
      if (st.planId != null && st.planMode) {
        restoreRef.current = navKey;
        if (st.planMode === 'workflow') startWorkflow(st.planId);
        else if (st.planMode === 'layer-practice') startByPlan(st.planId, 'layer-practice', st.layer);
        else startByPlan(st.planId, st.planMode, st.planMode === 'layer' ? st.layer : undefined);
        return;
      }
      if (st.conceptId != null) {
        restoreRef.current = navKey;
        if (st.openSubPoints) enterTeach(st.conceptId);
        else startByConcept(st.conceptId);
        return;
      }
      if (st.taskId != null) {
        restoreRef.current = navKey;
        startByTask(st.taskId);
        return;
      }
      if (st.mode === 'free') {
        restoreRef.current = 'free';
        startFree();
        return;
      }
    }

    if (st.viewQuestionId != null) {
      const qid = st.viewQuestionId;
      const key = 'browse:' + qid;
      if (restoreRef.current === key) return;
      restoreRef.current = key;
      setBrowseQid(qid);
      (async () => {
        setConvLoading(true); setErr('');
        try {
          const c = await drill.conversation(qid);
          setConv(c);
          // 该题有进行中的 LEARN run → 直接进入 live 聊天继续对话（历史问答已载入线程）
          const resumable = c.runs
            .filter((r) => r.mode === 'LEARN' && r.status !== 'GRADED')
            .sort((a, b) => b.runId - a.runId)[0];
          if (resumable) {
            if (sseRef.current) { sseRef.current.cancel(); sseRef.current = null; }
            if (typewriterRef.current) { clearInterval(typewriterRef.current); typewriterRef.current = null; }
            setMeta({
              runId: resumable.runId,
              questionId: c.questionId,
              probeType: c.probeType,
              responseFormat: c.responseFormat,
            });
            setMessages(convToMessages(c));
            setCtx(null);
            setInput('');
            setErr('');
            setGate('');
            setGrade(null);
            setSeconds(0);
            setResumedGraded(false);
            setPhase('chatting');
            setBrowseQid(null);
            navigate('/drill/learn');
          } else {
            // 全部已判分 → browse 页展示历史 + 继续对话（browseQid 仍存在，走 browse 视图）
          }
        } catch (e) { setErr(e instanceof ApiError ? e.message : '加载失败'); }
        finally { setConvLoading(false); }
      })();
      return;
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [location.state]);

  // ===== 题目生成：同步拿回 QuestionView → 打字机逐字展示题干 → 进入对话 =====
  const startQuestion = async (apiFn: () => Promise<QuestionView>, sessionCtx: SessionCtx) => {
    if (genLockRef.current) return;
    genLockRef.current = true;

    if (sseRef.current) { sseRef.current.cancel(); sseRef.current = null; }
    if (typewriterRef.current) { clearInterval(typewriterRef.current); typewriterRef.current = null; }

    setCtx(sessionCtx);
    setMeta(null);
    setMessages([]);
    setInput('');
    setErr('');
    setGate('');
    setSeconds(0);
    setGrade(null);
    setResumedGraded(false);
    setPhase('generating');
    setBrowseQid(null);
    // 进入做题页：从知识点页/首页进入 → 压历史（返回可回到来源页）；
    // 已是做题页（下一题）→ replace，避免每一题都堆积一条历史
    if (view === 'learn') navigate('/drill/learn', { replace: true });
    else navigate('/drill/learn');

    const stemId = nextMsgId();
    setMessages([{ id: stemId, role: 'ai', text: '', streaming: true, type: 'stem' }]);

    try {
      const q = await apiFn();
      setMeta({
        runId: q.runId,
        questionId: q.questionId,
        probeType: q.probeType,
        responseFormat: q.responseFormat,
      });
      // 「继续学习」恢复到进行中的题（之前答过但没结束）：把该 run 已有的问答历史载入聊天线程，
      // 别让之前聊过的内容消失。
      drill.conversation(q.questionId)
        .then((conv) => {
          const hist = runTurnsToMessages(conv, q.runId);
          if (hist.length > 0) {
            setMessages((prev) => [prev[0], ...hist, ...prev.slice(1)]);
          }
        })
        .catch(() => {
          // 全新题没有历史（conversation 404），忽略
        });
      const fullStem = q.stem || '';
      if (!fullStem) {
        setMessages((prev) => prev.map((mm) =>
          mm.id === stemId ? { ...mm, streaming: false, text: '（题目为空）' } : mm,
        ));
        setPhase('chatting');
        genLockRef.current = false;
        return;
      }
      let i = 0;
      const chunkSize = 2; // 每帧 reveal 2 字符
      const interval = setInterval(() => {
        i += chunkSize;
        if (i >= fullStem.length) {
          setMessages((prev) => prev.map((mm) =>
            mm.id === stemId ? { ...mm, text: fullStem, streaming: false } : mm,
          ));
          setPhase('chatting');
          genLockRef.current = false;
          if (typewriterRef.current) { clearInterval(typewriterRef.current); typewriterRef.current = null; }
        } else {
          setMessages((prev) => prev.map((mm) =>
            mm.id === stemId ? { ...mm, text: fullStem.slice(0, i) } : mm,
          ));
        }
      }, 15);
      typewriterRef.current = interval;
    } catch (e) {
      genLockRef.current = false;
      setPhase('chatting');
      setMessages((prev) => prev.map((mm) =>
        mm.id === stemId
          ? { ...mm, streaming: false, text: mm.text || '（题目生成失败）' }
          : mm,
      ));
      if (e instanceof ApiError && e.status === 409) {
        setGate(e.message);
      } else {
        setErr(e instanceof ApiError ? e.message : '题目生成失败');
      }
    }
  };

  const startFree = () => startQuestion(() => drill.next(), { kind: 'free' });

  // 直接出题（不开讲解）
  const directStart = (conceptId: number) =>
    startQuestion(() => drill.start(conceptId), { kind: 'concept', conceptId });

  // 播放某个子知识点的讲解（SSE 流式）；refresh=true 走「换种描述」：后端跳过缓存重新生成
  const playSubLesson = (conceptId: number, subPoint: string, refresh = false) => {
    setLessonText('');
    setLessonReasoning('');
    setLessonBusy(true);
    setErr('');
    if (sseRef.current) { sseRef.current.cancel(); sseRef.current = null; }
    sseRef.current = lessonStream(
      conceptId,
      subPoint,
      (token) => setLessonText((prev) => prev + token),
      (reasoning) => setLessonReasoning((prev) => prev + reasoning),
      () => { setLessonBusy(false); sseRef.current = null; },
      (_status, msg) => {
        setLessonBusy(false);
        sseRef.current = null;
        setErr(msg || '讲解生成失败');
      },
      refresh,
    );
  };

  // 进入「先教后考」：跳子路由，由路由 effect 拉子知识点清单
  const enterTeach = (conceptId: number) => {
    navigate(`/drill/teach/${conceptId}`);
  };

  const startByConcept = (conceptId: number) => {
    if (teachFirst) enterTeach(conceptId);
    else directStart(conceptId);
  };

  // 点某个子知识点：跳子路由（讲解页），由路由 effect 播放讲解
  const openSubPoint = (idx: number) => {
    if (!teach) return;
    navigate(`/drill/teach/${teach.conceptId}/${idx}`);
  };

  /** 手动「直接通过 / 取消通过」当前子知识点（简单知识点跳过做题；可撤销）。 */
  // Electron 不支持 window.confirm/prompt，确认/输入一律走应用内弹窗
  const [pendingConfirm, setPendingConfirm] = useState<{
    title: string;
    message: string;
    confirmText?: string;
    danger?: boolean;
    action: () => Promise<void> | void;
  } | null>(null);
  const [promptOpen, setPromptOpen] = useState(false);

  const applySubPointPass = async (sub: string, passed: boolean) => {
    if (!teach) return;
    try {
      await drill.subPointPass(teach.conceptId, sub, passed);
      const done = passed
        ? teach.done.includes(sub) ? teach.done : [...teach.done, sub]
        : teach.done.filter((s) => s !== sub);
      setTeach({ ...teach, done });
    } catch (e) {
      setErr(`操作失败：${e instanceof Error ? e.message : String(e)}`);
    }
  };

  const toggleSubPointPass = () => {
    if (!teach || teach.curIdx < 0) return;
    const sub = teach.subPoints[teach.curIdx];
    const isDone = teach.done.includes(sub);
    if (isDone) {
      // 取消通过：撤销操作，无需确认
      void applySubPointPass(sub, false);
    } else {
      setPendingConfirm({
        title: '跳过此子知识点？',
        message: `确定跳过「${sub}」吗？该子知识点将被标记为已通过，无需做题。`,
        confirmText: '跳过',
        action: () => applySubPointPass(sub, true),
      });
    }
  };

  /** 用户补充一个子知识点（写入缓存；讲解首次打开时按需生成）。 */
  const confirmAddSubPoint = async (name: string) => {
    if (!teach) return;
    const sp = name.trim();
    if (!sp) return;
    const o = await drill.addSubPoint(teach.conceptId, sp);
    setTeach({ ...teach, subPoints: o.subPoints, done: o.completedSubPoints ?? [] });
    setErr('');
  };

  /** 删除一个子知识点（AI 拆的或用户补充的都行）：讲解缓存与通过记录一并清理。 */
  const confirmRemoveSubPoint = async (sp: string) => {
    if (!teach) return;
    const o = await drill.removeSubPoint(teach.conceptId, sp);
    setTeach({ ...teach, subPoints: o.subPoints, done: o.completedSubPoints ?? [] });
    setErr('');
  };

  const removeSubPoint = (sp: string) => {
    setPendingConfirm({
      title: '删除子知识点',
      message: `确定删除子知识点「${sp}」吗？它的讲解缓存与「通过」记录会一并删除。`,
      confirmText: '删除',
      danger: true,
      action: () => confirmRemoveSubPoint(sp),
    });
  };

  /** 从统一工作流直接进入指定的下一个子知识点，不再先展示一整棵重复的概念选择树。 */
  const enterTeachAt = (conceptId: number, planId?: number, taskId?: number, subIdx?: number) => {
    const qs = new URLSearchParams();
    if (planId != null) qs.set('plan', String(planId));
    if (taskId != null) qs.set('task', String(taskId));
    const q = qs.toString();
    if (subIdx != null && subIdx >= 0) {
      navigate(`/drill/teach/${conceptId}/${subIdx}${q ? `?${q}` : ''}`);
    } else {
      navigate(`/drill/teach/${conceptId}${q ? `?${q}` : ''}`);
    }
  };

  /** 后端决定唯一下一步：L1→L5、子点教学练习、知识点检测、层级检测。 */
  const startWorkflow = async (planId: number) => {
    setErr('');
    try {
      const next = await drill.learningNext(planId);
      if (next.stepType === 'SUB_POINT' && next.conceptId != null && next.subPoint) {
        await enterTeachAt(next.conceptId, planId, undefined, next.subPointIndex);
      } else if (next.stepType === 'CONCEPT_ASSESSMENT' && next.conceptId != null) {
        startQuestion(
          () => drill.startPlan(planId, 'concept-assessment', next.layer, next.conceptId!),
          { kind: 'assessment', planId, mode: 'concept-assessment', layer: next.layer, conceptId: next.conceptId },
        );
      } else if (next.stepType === 'LEVEL_ASSESSMENT') {
        startQuestion(
          () => drill.startPlan(planId, 'level-assessment', next.layer),
          { kind: 'assessment', planId, mode: 'level-assessment', layer: next.layer },
        );
      } else {
        setErr(next.message || '这个学习方向已经完成');
        navigate('/drill');
      }
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : '无法读取下一学习任务');
      navigate('/drill');
    }
  };

  // 讲完当前子点，开始做题（限定到该子点）
  const startSubQuiz = () => {
    if (!teach || teach.curIdx < 0) return;
    const t = teach;
    startQuestion(
      () => drill.start(t.conceptId, t.subPoints[t.curIdx]),
      { kind: 'teach', conceptId: t.conceptId, subIndex: t.curIdx, planId: t.planId, taskId: t.taskId },
    );
  };

  // 基于整个知识点出题（范围 = 已学内容 + 整个知识点），不只考单个子知识点
  const practiceWholeConcept = () => {
    if (!teach) return;
    const t = teach;
    startQuestion(
      () => drill.startPlan(t.planId, 'concept-practice', undefined, t.conceptId),
      { kind: 'scoped', planId: t.planId, scope: 'concept', conceptId: t.conceptId, layer: undefined },
    );
  };

  const toggleTeachFirst = (val: boolean) => {
    setTeachFirst(val);
    try { localStorage.setItem(TEACH_FIRST_KEY, val ? '1' : '0'); } catch { /* ignore */ }
  };

  const startByPlan = (planId: number, mode: 'continue' | 'review' | 'layer' | 'layer-practice', layer?: number) =>
    startQuestion(() => drill.startPlan(planId, mode, layer), { kind: 'plan', planId, mode, layer });

  // 连续今日任务：复习直接答题；新学任务由页面先进入该知识点的下一个子知识点教学。
  const startByTask = async (taskId: number) => {
    try {
      const task = (await drill.today()).find((t) => t.id === taskId);
      if (task?.kind === 'NEW') {
        const outline = await drill.outline(task.conceptId);
        const subIdx = outline.subPoints.findIndex((s) => !outline.completedSubPoints.includes(s));
        if (subIdx >= 0) {
          await enterTeachAt(task.conceptId, task.planId ?? undefined, taskId, subIdx);
          return;
        }
      }
    } catch { /* 读取失败降级为原有预生成题 */ }
    startQuestion(() => drill.startTask(taskId), { kind: 'task' });
  };

  const cleanErr = (msg?: string) => {
    if (!msg) return '';
    try { return (JSON.parse(msg) as { message?: string }).message ?? msg; } catch { return msg; }
  };

  // ===== 图片上传（截图/粘贴，仅视觉模型开放）=====
  const MAX_IMAGES = 4;
  const MAX_IMG_PX = 1280;

  /** 压缩图片为 data URL：长边 ≤1280、JPEG 质量 0.85（PNG 保留透明）。 */
  const compressImage = (file: File): Promise<string> =>
    new Promise((resolve, reject) => {
      const url = URL.createObjectURL(file);
      const img = new Image();
      img.onload = () => {
        try {
          const scale = Math.min(1, MAX_IMG_PX / Math.max(img.width, img.height));
          const w = Math.max(1, Math.round(img.width * scale));
          const h = Math.max(1, Math.round(img.height * scale));
          const canvas = document.createElement('canvas');
          canvas.width = w; canvas.height = h;
          const ctx = canvas.getContext('2d');
          if (!ctx) throw new Error('canvas 不可用');
          ctx.drawImage(img, 0, 0, w, h);
          const isPng = file.type === 'image/png';
          resolve(canvas.toDataURL(isPng ? 'image/png' : 'image/jpeg', 0.85));
        } catch (e) { reject(e); }
        finally { URL.revokeObjectURL(url); }
      };
      img.onerror = () => { URL.revokeObjectURL(url); reject(new Error('图片读取失败')); };
      img.src = url;
    });

  const addImageFiles = async (files: FileList | File[] | null) => {
    if (!files || files.length === 0) return;
    const list = Array.from(files).filter((f) => f.type.startsWith('image/')).slice(0, MAX_IMAGES);
    if (list.length === 0) { setErr('请选择图片文件'); return; }
    try {
      const dataUrls = await Promise.all(list.map(compressImage));
      setImages((prev) => [...prev, ...dataUrls].slice(0, MAX_IMAGES));
      setErr('');
    } catch (e) {
      setErr(e instanceof Error ? e.message : '图片处理失败');
    }
  };

  /** 输入框粘贴：剪贴板里有截图（image）时直接附加。 */
  const onInputPaste = (e: React.ClipboardEvent) => {
    const items = e.clipboardData?.items;
    if (!items) return;
    const imgs: File[] = [];
    for (const it of Array.from(items)) {
      if (it.type.startsWith('image/')) {
        const f = it.getAsFile();
        if (f) imgs.push(f);
      }
    }
    if (imgs.length > 0) {
      e.preventDefault();
      void addImageFiles(imgs);
    }
  };

  /** 输入框工具栏：插入 ```语言 代码块模板，光标落到块内。 */
  const insertCodeBlock = () => {
    const ta = document.querySelector<HTMLTextAreaElement>('.chat-input-textarea');
    const el = ta ?? document.querySelector('textarea.chat-input-textarea');
    const start = el?.selectionStart ?? input.length;
    const end = el?.selectionEnd ?? input.length;
    const block = '```语言\n\n```';
    const next = input.slice(0, start) + block + input.slice(end);
    setInput(next);
    requestAnimationFrame(() => {
      if (el) el.selectionStart = el.selectionEnd = start + '```语言\n'.length;
      el?.focus();
    });
  };

  // ===== 对话 SSE：用户发消息（作答或追问）→ 「思考中…」→ AI 逐 token 回复 → done =====
  // reveal=true 为「看答案」：不需要输入内容，直接向 AI 索要完整答案；服务端会记录
  // 答案揭示边界，之后的回答不再计入评分。
  const sendAnswer = (reveal = false) => {
    if (!meta || phase !== 'chatting') return;
    // 防御：若仍有未结束的流（异常情况下），先取消再发新消息，避免旧流残留
    if (sseRef.current) { sseRef.current.cancel(); sseRef.current = null; }
    const userText = reveal ? '我想直接看答案' : input.trim();
    if (!reveal && !userText) return;
    const sendingImages = images;
    setInput('');
    setImages([]);

    const userMsgId = nextMsgId();
    const aiMsgId = nextMsgId();

    // 乐观上屏：用户消息（含图片）+ AI 占位（streaming=true, text='' → 显示"思考中…"）
    setMessages((prev) => [
      ...prev,
      { id: userMsgId, role: 'me', text: userText, type: 'chat', images: sendingImages },
      { id: aiMsgId, role: 'ai', text: '', streaming: true, type: 'chat' },
    ]);

    sseRef.current = chatStream(
      meta.runId,
      userText,
      reveal,
      (token) => {
        setMessages((prev) => prev.map((mm) =>
          mm.id === aiMsgId ? { ...mm, text: mm.text + token } : mm,
        ));
      },
      (reasoning) => {
        setMessages((prev) => prev.map((mm) =>
          mm.id === aiMsgId ? { ...mm, reasoning: (mm.reasoning ?? '') + reasoning } : mm,
        ));
      },
      () => {
        setMessages((prev) => prev.map((mm) =>
          mm.id === aiMsgId ? { ...mm, streaming: false } : mm,
        ));
        sseRef.current = null;
      },
      (status?: number, message?: string) => {
        setMessages((prev) => prev.map((mm) =>
          mm.id === aiMsgId
            ? { ...mm, streaming: false, text: mm.text || '（回复失败）' }
            : mm,
        ));
        sseRef.current = null;
        if (status === 409) {
          setGate(cleanErr(message) || '已有未完成的作答');
        } else {
          setErr(cleanErr(message) || '回复失败');
        }
      },
      () => {
        // event:reveal —— 服务端已揭示答案：标记该 AI 回复为「参考答案」，
        // 让用户知道从此处开始是参考答案、此后的回答不再计入评分。
        setMessages((prev) => prev.map((mm) =>
          mm.id === aiMsgId ? { ...mm, revealed: true } : mm,
        ));
      },
      sendingImages,
    );
  };

  // ===== 暂停 AI 回复：取消当前 SSE，未答完的回复保留在界面但不落库 =====
  const pauseReply = () => {
    if (sseRef.current) { sseRef.current.cancel(); sseRef.current = null; }
    setMessages((prev) => prev.map((mm) =>
      mm.streaming ? { ...mm, streaming: false, paused: true } : mm,
    ));
  };

  // ===== 结束并评分：基于整轮对话一次性判分 =====
  const finishAndGrade = async () => {
    if (!meta) return;
    setPhase('finishing');
    setErr('');
    try {
      const g = await drill.finish(meta.runId);
      setGrade(g);
      if (g.rawScore >= 60 && ctx?.kind === 'teach' && ctx.taskId != null) {
        drill.completeTask(ctx.taskId).catch(() => {});
      }
      setPhase('graded');
    } catch (e) {
      setPhase('chatting');
      if (e instanceof ApiError && e.status === 409) setGate(e.message);
      else setErr(e instanceof ApiError ? e.message : '评分失败');
    }
  };

  // ===== 下一题：按 ctx 抽新题（走 SSE） =====
  const goNext = () => {
    if (!ctx) {
      setErr('当前会话没有选题上下文，请点「换个方向」回到学习计划重新开始。');
      return;
    }
    if (genLockRef.current) return;
    switch (ctx.kind) {
      case 'plan':
        startByPlan(ctx.planId, ctx.mode, ctx.layer);
        break;
      case 'workflow':
        startWorkflow(ctx.planId);
        break;
      case 'assessment':
        // 一道综合题完成后重新询问工作流；达到题数就自动进入下一知识点或下一层。
        startWorkflow(ctx.planId);
        break;
      case 'scoped': {
        // 整知识点 / 整层级练习：下一题继续按同一范围出题
        if (ctx.scope === 'concept' && ctx.conceptId != null) {
          startQuestion(
            () => drill.startPlan(ctx.planId, 'concept-practice', undefined, ctx.conceptId!),
            { kind: 'scoped', planId: ctx.planId, scope: 'concept', conceptId: ctx.conceptId, layer: ctx.layer },
          );
        } else if (ctx.scope === 'layer' && ctx.planId != null) {
          startQuestion(
            () => drill.startPlan(ctx.planId, 'layer-practice', ctx.layer),
            { kind: 'scoped', planId: ctx.planId, scope: 'layer', layer: ctx.layer },
          );
        }
        break;
      }
      case 'concept':
        startByConcept(ctx.conceptId);
        break;
      case 'teach': {
        if (!teach) { directStart(ctx.conceptId); break; }
        const curSub = teach.subPoints[ctx.subIndex] ?? '';
        // 子知识点只有达到统一及格线才标记“达标”；低分仍保留在清单中供重新练习。
        const passed = grade != null && grade.rawScore >= 60;
        const done = passed && curSub && !teach.done.includes(curSub) ? [...teach.done, curSub] : teach.done;
        const nextIdx = ctx.subIndex + 1;
        if (nextIdx < teach.subPoints.length) {
          // 还有下一个子点：讲它（子路由 → 讲解页）
          setTeach({ ...teach, curIdx: nextIdx, done });
          navigate(`/drill/teach/${teach.conceptId}/${nextIdx}`);
        } else if (ctx.planId != null) {
          // 当前大知识点的全部子点已处理，交回工作流决定重练未通过子点或开始综合检测。
          startWorkflow(ctx.planId);
        } else {
          setTeach({ ...teach, curIdx: -1, done });
          navigate(`/drill/teach/${teach.conceptId}`);
        }
        break;
      }
      case 'task':
        // 今日任务：自动接下一道预生成题（无则后端 404，前端显示提示）
        startQuestion(() => drill.nextTask(), { kind: 'task' });
        break;
      case 'free':
      default:
        startFree();
    }
  };

  const goHome = () => {
    if (sseRef.current) { sseRef.current.cancel(); sseRef.current = null; }
    if (typewriterRef.current) { clearInterval(typewriterRef.current); typewriterRef.current = null; }
    setMeta(null);
    setMessages([]);
    setInput('');
    setCtx(null);
    setGate('');
    setErr('');
    setGrade(null);
    setResumedGraded(false);
    setBrowseQid(null);
    setTeach(null);
    setLessonText('');
    setLessonReasoning('');
    setLessonBusy(false);
    setOutlineBusy(false);
    restoreRef.current = null;
    // replace：换个方向即离开当前会话，返回键不应回到已放弃的页面
    navigate('/drill', { replace: true });
  };

  // ===== 从浏览模式「继续对话」：恢复该题最新的 LEARN run 进入 live 聊天。
  //     进行中的 run 继续作答（可结束评分）；已判分的 run 进入「用户向 AI 提问」，
  //     历史问答保留在聊天线程顶部，不重新评分。 =====
  const resumeFromBrowse = () => {
    if (!conv) return;

    // 找最新的 LEARN run（含进行中 READY/ANSWERING 与已判分 GRADED）
    const latestLearn = conv.runs
      .filter(r => r.mode === 'LEARN')
      .sort((a, b) => b.runId - a.runId)[0];
    if (!latestLearn) return;

    // 清旧流
    if (sseRef.current) { sseRef.current.cancel(); sseRef.current = null; }
    if (typewriterRef.current) { clearInterval(typewriterRef.current); typewriterRef.current = null; }

    setMeta({
      runId: latestLearn.runId,
      questionId: conv.questionId,
      probeType: conv.probeType,
      responseFormat: conv.responseFormat,
    });
    setMessages(convToMessages(conv));
    setCtx(null);
    setInput('');
    setErr('');
    setGate('');
    setGrade(null);
    setSeconds(0);
    setResumedGraded(latestLearn.status === 'GRADED');
    setPhase('chatting');
    setBrowseQid(null);
    navigate('/drill/learn');
  };

  // ===== 从浏览模式重练同一题（基于已 GRADED run 开新 run）=====
  const restartFromBrowse = () => {
    if (!conv || conv.runs.length === 0) return;
    const latestLearn = conv.runs
      .filter(r => r.mode === 'LEARN')
      .sort((a, b) => b.runId - a.runId)[0];
    if (!latestLearn) return;
    startQuestion(() => drill.restart(latestLearn.runId), { kind: 'free' });
  };

  const openNote = () => {
    if (!meta) return;
    const stemText = messages.find((m) => m.type === 'stem')?.text ?? '';
    setNoteStem(stemText);
    setNoteRunId(meta.runId);
  };

  // ===== 闸门冲突（409）=====
  if (gate) {
    return (
      <div className="page">
        <header className="page-head">
          <span className="eyebrow">练习 · 状态冲突</span>
          <h1>手头还有未完成的作答</h1>
        </header>
        <div className="gate-card card">
          <p>{gate}</p>
          <p className="gate-hint">
            同一时间只做一道题。回到练习页重进，会自动恢复那道未完成的题。
          </p>
          <Button variant="ghost" onClick={() => { setGate(''); goHome(); }}>
            <Compass size={16} strokeWidth={1.6} /> 回到练习页
          </Button>
        </div>
      </div>
    );
  }

  // ===== Home（选方向）=====
  if (view === 'home') {
    return (
      <Plans
        plans={plans}
        onPick={startByConcept}
        onContinue={(id) => startWorkflow(id)}
        onReview={(id) => startByPlan(id, 'review')}
        onFree={startFree}
        onStartTask={startByTask}
        err={planErr}
        teachFirst={teachFirst}
        onToggleTeachFirst={toggleTeachFirst}
      />
    );
  }

  // ===== Browse（历史浏览）=====
  if (browseMode) {
    const resumableRun = conv?.runs
      .filter(r => r.mode === 'LEARN' && r.status !== 'GRADED')
      .sort((a, b) => b.runId - a.runId)[0];
    const hasGradedLearn = conv?.runs.some(r => r.mode === 'LEARN' && r.status === 'GRADED');
    const latestLearn = conv?.runs
      .filter(r => r.mode === 'LEARN')
      .sort((a, b) => b.runId - a.runId)[0];

    return (
      <div className="page">
        <header className="page-head">
          <span className="eyebrow">对话历史 · 浏览</span>
          <h1>查看这道题的对话</h1>
          <button className="head-back" onClick={goHome}>
            <Compass size={14} strokeWidth={1.6} /> 返回练习
          </button>
        </header>
        {err && <div className="banner info">{err}</div>}
        <div className="chat-panel">
          <div className="chat-thread card drill-conv">
            <div className="chat-row chat-row-ai">
              <div className="chat-avatar chat-avatar-ai"><span>AI</span></div>
              <div className="chat-bubble chat-bubble-ai is-stem">
                <Markdown>{conv?.stem ?? '加载中…'}</Markdown>
              </div>
            </div>
            {convLoading ? (
              <div className="chat-row chat-row-ai chat-row-loading">
                <div className="chat-bubble chat-bubble-ai is-loading">
                  <span className="spinner-sm" /> 读取对话历史…
                </div>
              </div>
            ) : conv && conv.runs.length > 0 ? (
              <ConversationStream conv={conv} showStem={false} />
            ) : conv != null ? (
              <div className="chat-row chat-row-ai">
                <div className="chat-bubble chat-bubble-ai is-muted">
                  这道题还没有对话记录。
                </div>
              </div>
            ) : !err ? (
              <div className="chat-row chat-row-ai chat-row-loading">
                <div className="chat-bubble chat-bubble-ai is-loading">
                  <span className="spinner-sm" /> 读取对话历史…
                </div>
              </div>
            ) : null}
          </div>
          <div className="chat-input card">
            <p className="chat-input-hint">
              {resumableRun
                ? '这道题有未完成的对话，可以继续作答。'
                : hasGradedLearn
                  ? '这道题已练习完毕，可以继续向 AI 提问（不会重新评分），或重练同一题。'
                  : '查看这道题的对话历史。'}
            </p>
            <div className="chat-input-foot">
              <Button variant="ghost" onClick={goHome}>
                <Compass size={14} strokeWidth={1.6} /> 返回练习
              </Button>
              <div className="chat-input-actions">
                {latestLearn && (
                  <Button onClick={resumeFromBrowse} disabled={convLoading}>
                    继续对话
                  </Button>
                )}
                {latestLearn?.status === 'GRADED' && (
                  <Button variant="ghost" onClick={restartFromBrowse} disabled={convLoading}>
                    重练此题
                  </Button>
                )}
              </div>
            </div>
          </div>
        </div>
        <NoteDialog runId={noteRunId} stem={noteStem} onClose={() => setNoteRunId(null)} onSaved={() => setNoteRunId(null)} />
      </div>
    );
  }

  // ===== Teach（先教后考：子知识点清单 + 逐个讲解）=====
  if (view === 'teach') {
    const t = teach;
    return (
      <div className="page chat-page teach-page">
        <header className="page-head chat-head">
          <div className="chat-head-title">
            <span className="eyebrow">先教后考</span>
            <h1>{t?.name || '知识点讲解'}</h1>
            {t?.topic ? <p className="teach-head-topic">{t.topic}</p> : null}
          </div>
          <div className="head-actions">
            <button className="head-back" onClick={backOrHome} title="返回上一页（也支持鼠标后退键）">
              <ArrowLeft size={14} strokeWidth={1.6} /> 返回
            </button>
            <button className="head-back" onClick={goHome}>
              <Compass size={14} strokeWidth={1.6} /> 换个方向
            </button>
          </div>
        </header>

        {err && <div className="banner info">{err}</div>}

        <div className="teach-panel">
          <label className="teach-toggle">
            <input
              type="checkbox"
              checked={teachFirst}
              onChange={(e) => toggleTeachFirst(e.target.checked)}
            />
            先讲解再练习（点知识点时先拆子点、逐个教考）
          </label>

          {outlineBusy ? (
            <div className="card teach-card">
              <div className="chat-row chat-row-ai chat-row-loading">
                <div className="chat-bubble chat-bubble-ai is-loading">
                  <span className="spinner-sm" /> 正在拆解子知识点…
                </div>
              </div>
            </div>
          ) : t && t.subPoints.length > 0 && t.curIdx < 0 ? (
            // 清单页：展示子知识点列表
            <div className="card teach-card">
              <h2 className="teach-title">这个概念包含 {t.subPoints.length} 个子知识点</h2>
              <p className="teach-hint">逐个点击：先听讲解、再做题，直到全部学完。拆得不合适可以自己增删。</p>
              <ul className="teach-outline">
                {t.subPoints.map((sp, i) => {
                  const isDone = t.done.includes(sp);
                  return (
                    <li key={`${sp}-${i}`} className="teach-sub-item">
                      <button
                        className={'teach-sub' + (isDone ? ' is-done' : '')}
                        onClick={() => openSubPoint(i)}
                      >
                        <span className="teach-sub-num">{i + 1}</span>
                        <span className="teach-sub-name">{sp}</span>
                        {isDone && <span className="teach-sub-check">✓</span>}
                      </button>
                      <button
                        className="teach-sub-del"
                        onClick={() => removeSubPoint(sp)}
                        title={`删除子知识点「${sp}」（讲解缓存与通过记录一并清理）`}
                      >
                        <X size={13} strokeWidth={1.8} />
                      </button>
                    </li>
                  );
                })}
              </ul>
              <div className="teach-foot">
                <div className="teach-foot-left">
                  <Button variant="ghost" onClick={() => setPromptOpen(true)} title="AI 拆漏了或想换个粒度？自己补充一个子知识点（讲解首次打开时生成）">
                    <Plus size={15} strokeWidth={1.6} /> 补充一下
                  </Button>
                  <Button variant="ghost" onClick={practiceWholeConcept} title="基于这个知识点整体出题：范围 = 已学内容 + 整个知识点，覆盖多个子知识点">
                    <Target size={15} strokeWidth={1.6} /> 按整个知识点出题
                  </Button>
                </div>
              </div>
            </div>
          ) : t && t.curIdx >= 0 ? (
            // 讲解页：讲当前子知识点
            <div className="card teach-card">
              <div className="teach-lesson-head">
                <button className="teach-back" onClick={() => t && navigate(`/drill/teach/${t.conceptId}`)}>
                  <ArrowLeft size={14} strokeWidth={1.6} /> 返回清单
                </button>
                <span className="teach-lesson-eyebrow">
                  子知识点 {t.curIdx + 1} / {t.subPoints.length}
                </span>
                <h2 className="teach-title">{t.subPoints[t.curIdx]}</h2>
              </div>
              <div className="teach-lesson-body">
                {lessonBusy && !lessonText ? (
                  <div className="chat-row chat-row-ai chat-row-loading">
                    <div className="chat-bubble chat-bubble-ai is-loading">
                      <span className="spinner-sm" /> 正在备课…
                    </div>
                  </div>
                ) : (
                  <>
                    {lessonReasoning && (
                      <details className="reasoning-panel" open>
                        <summary>AI 思考过程</summary>
                        <div className="reasoning-text"><Markdown>{lessonReasoning}</Markdown></div>
                      </details>
                    )}
                    <div className="tutor-text">
                      <Markdown>{lessonText || '（讲解内容为空）'}</Markdown>
                      {lessonBusy && <span className="tutor-caret" aria-hidden />}
                    </div>
                  </>
                )}
              </div>
              <div className="teach-foot">
                <div className="teach-foot-left">
                  <Button variant="ghost" onClick={() => t && navigate(`/drill/teach/${t.conceptId}`)}>
                    返回清单
                  </Button>
                  <Button
                    variant="ghost"
                    onClick={() => t && playSubLesson(t.conceptId, t.subPoints[t.curIdx], true)}
                    disabled={lessonBusy}
                    title="用另一种说法、另一组例子重新讲解这个子知识点"
                  >
                    <RefreshCw size={14} strokeWidth={1.8} className={lessonBusy ? 'spin' : ''} /> 换种描述
                  </Button>
                  <Button
                    variant={t.done.includes(t.subPoints[t.curIdx]) ? 'primary' : 'ghost'}
                    onClick={toggleSubPointPass}
                    disabled={lessonBusy}
                    title={
                      t.done.includes(t.subPoints[t.curIdx])
                        ? '该子知识点已标记通过；点击取消通过，恢复为待学习'
                        : '这个子知识点很简单，可以直接标记为已通过，跳过做题'
                    }
                  >
                    {t.done.includes(t.subPoints[t.curIdx]) ? '取消通过' : '直接通过'}
                  </Button>
                </div>
                <Button onClick={startSubQuiz} disabled={lessonBusy}>
                  开始做题 <ChevronRight size={16} strokeWidth={1.6} />
                </Button>
              </div>
            </div>
          ) : null}
        </div>

        {/* 应用内弹窗：Electron 不支持 window.confirm/prompt */}
        {pendingConfirm && (
          <ConfirmDialog
            title={pendingConfirm.title}
            message={pendingConfirm.message}
            confirmText={pendingConfirm.confirmText}
            danger={pendingConfirm.danger}
            onConfirm={() => {
              const done = pendingConfirm.action();
              void Promise.resolve(done).then(() => setPendingConfirm(null));
            }}
            onClose={() => setPendingConfirm(null)}
          />
        )}
        {promptOpen && (
          <PromptDialog
            title="补充一个子知识点"
            placeholder="例如：异常处理的最佳实践"
            submitText="加入"
            onSubmit={confirmAddSubPoint}
            onClose={() => setPromptOpen(false)}
          />
        )}
      </div>
    );
  }

  // ===== Learn（聊天式 SSE 练习）=====
  const canSend = phase === 'chatting' && input.trim().length > 0;
  // AI 正在流式回复中：显示「暂停」而不是发送（回车=换行，Ctrl/⌘+回车=发送）
  const aiStreaming = messages.some((m) => m.streaming);
  // 至少有一轮用户对话才能结束评分；已判分 run 上继续的对话不再重新评分，隐藏该按钮
  const canFinish =
    phase === 'chatting' &&
    messages.some((m) => m.type === 'chat' && m.role === 'me') &&
    !resumedGraded;
  const hasLowGrade = grade != null && grade.rawScore < 60;

  return (
    <div className="page chat-page">
      <header className="page-head chat-head">
        <div className="chat-head-title">
          <span className="eyebrow">练习 · LEARN</span>
          <h1>练习</h1>
        </div>
        <div className="head-actions">
          <button className="head-back" onClick={backOrHome} title="返回上一页（也支持鼠标后退键）">
            <ArrowLeft size={14} strokeWidth={1.6} /> 返回
          </button>
          <button className="head-back" onClick={goHome}>
            <Compass size={14} strokeWidth={1.6} /> 换个方向
          </button>
        </div>
      </header>

      {err && <div className="banner info">{err}</div>}

      <div className="chat-panel">
        {/* 聊天线程：题干(AI) → 用户答 → AI回复 → …（不含评分）。整页随内容自然滚动，不在线程内滚动。 */}
        <div className="chat-thread card drill-conv">
          {messages.map((m) => (
            <ChatBubble
              key={m.id}
              msg={m}
              meta={meta}
              timingOn={timingOn}
              seconds={seconds}
            />
          ))}
        </div>

        {/* 输入区：按 phase 分态（resumedGraded 时隐藏「结束并评分」） */}
        <div className="chat-input card">
          {phase === 'graded' ? (
            // 已评分：写笔记 + 下一题
            <div className="chat-input-foot">
              <div className="chat-input-actions">
                {hasLowGrade && (
                  <Button variant="ghost" onClick={openNote}>
                    <NotebookPen size={16} strokeWidth={1.6} /> 写内化笔记
                  </Button>
                )}
              </div>
              <Button onClick={goNext}>
                下一题 <ChevronRight size={16} strokeWidth={1.6} />
              </Button>
            </div>
          ) : phase === 'finishing' ? (
            // 评分中：禁用
            <div className="chat-input-foot">
              <span className="chat-input-hint">正在基于整轮对话评分…</span>
              <Button disabled>
                <span className="spinner spinner-inline" /> 评分中
              </Button>
            </div>
          ) : (
            // 对话 / 生成中：输入框 + 发送（已判分继续时提示不重新评分）
            <>
              <div className="chat-input-tools">
                <button type="button" className="chat-tool-btn" onClick={insertCodeBlock} title="插入代码块（```语言 包裹）">
                  <Code2 size={14} strokeWidth={1.8} /> 代码块
                </button>
                {vision && (
                  <button type="button" className="chat-tool-btn" onClick={() => fileRef.current?.click()} title="上传/粘贴截图，AI 可直接看图">
                    <ImagePlus size={14} strokeWidth={1.8} /> 图片
                  </button>
                )}
                {vision && <span className="chat-tool-hint">截图可直接 Ctrl+V 粘贴</span>}
              </div>
              <input
                ref={fileRef}
                type="file"
                accept="image/*"
                multiple
                hidden
                onChange={(e) => {
                  void addImageFiles(e.target.files);
                  e.target.value = '';
                }}
              />
              {images.length > 0 && (
                <div className="chat-img-preview">
                  {images.map((src, i) => (
                    <div className="chat-img-thumb" key={i}>
                      <img src={src} alt={`待发送图片 ${i + 1}`} />
                      <button
                        type="button"
                        className="chat-img-remove"
                        title="移除图片"
                        onClick={() => setImages((prev) => prev.filter((_, j) => j !== i))}
                      >
                        <X size={12} strokeWidth={2.2} />
                      </button>
                    </div>
                  ))}
                </div>
              )}
              <textarea
                className="chat-input-textarea"
                placeholder={
                  phase === 'generating'
                    ? '题目生成中…'
                    : resumedGraded
                      ? '向 AI 提问，继续聊这道题（已判分，不会重新评分）。回车换行，Ctrl/⌘+回车发送。'
                      : '先回答主问题；AI 会判断你是否理解，再视情况逐条追问（最多 4 个小问，也可能不追问）。回车换行，Ctrl/⌘+回车发送；想直接看答案可点「看答案」。'
                }
                value={input}
                disabled={phase === 'generating'}
                spellCheck={false}
                autoComplete="off"
                autoCorrect="off"
                autoCapitalize="off"
                onChange={(e) => setInput(e.target.value)}
                onPaste={onInputPaste}
                onCompositionStart={() => { composingRef.current = true; }}
                onCompositionEnd={() => { composingRef.current = false; }}
                onKeyDown={(e) => {
                  if (composingRef.current || e.nativeEvent.isComposing || e.keyCode === 229) return;
                  if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
                    // Ctrl/⌘+回车 发送；回车本身是换行，方便粘贴/编写代码
                    e.preventDefault();
                    sendAnswer(false);
                  } else if (e.key === 'Tab') {
                    // Tab 插入两个空格缩进（代码输入友好），保持光标位置
                    e.preventDefault();
                    const el = e.currentTarget;
                    const start = el.selectionStart ?? input.length;
                    const end = el.selectionEnd ?? input.length;
                    const next = input.slice(0, start) + '  ' + input.slice(end);
                    setInput(next);
                    requestAnimationFrame(() => {
                      el.selectionStart = el.selectionEnd = start + 2;
                    });
                  }
                }}
                rows={7}
              />
              <div className="chat-input-foot">
                <div className="chat-input-actions">
                  <button
                    className={'timing-toggle' + (timingOn ? ' on' : '')}
                    onClick={() => setTimingOn((v) => !v)}
                  >
                    <Timer size={15} strokeWidth={1.8} />
                    {timingOn ? `计时 ${fmt(seconds)}` : '开启计时'}
                  </button>
                </div>
                <div className="chat-input-actions">
                  {canFinish && (
                    <Button
                      variant="ghost"
                      onClick={finishAndGrade}
                      disabled={phase !== 'chatting' || aiStreaming}
                    >
                      结束并评分
                    </Button>
                  )}
                  {phase === 'chatting' && !resumedGraded && (
                    <Button
                      variant="ghost"
                      onClick={() => sendAnswer(true)}
                      disabled={aiStreaming}
                      title="直接看参考答案（此后的回答不再计入评分）"
                    >
                      看答案
                    </Button>
                  )}
                  {aiStreaming ? (
                    <Button variant="danger" onClick={pauseReply} title="暂停 AI 回复：未答完的内容不会保存">
                      暂停
                    </Button>
                  ) : (
                    <Button onClick={() => sendAnswer(false)} disabled={!canSend}>
                      {phase === 'generating' ? '等待题目' : '发送'}
                    </Button>
                  )}
                </div>
              </div>
            </>
          )}
        </div>

        {/* 评分总结：独立于聊天线程，显示在输入框下方 */}
        {grade && (
          <div className="chat-verdict card">
            <VerdictPanel
              run={{
                runId: grade.runId,
                mode: 'LEARN',
                status: 'GRADED',
                sourceRunId: null,
                rawScore: grade.rawScore,
                grade: grade.grade,
                answeredAt: '',
                turns: [],
              }}
              turn={{
                round: 0,
                stem: '',
                rawAnswer: '',
                rawScore: grade.rawScore,
                passed: grade.rawScore >= 60,
                byConceptJson: grade.byConceptJson,
                tutorText: null,
              }}
            />
          </div>
        )}

        {/* 整页滚动锚点：新消息 / 评分出现后滚到这里，保证最新气泡与输入框都在视野内 */}
        <div ref={endRef} aria-hidden />
      </div>

      <NoteDialog
        runId={noteRunId}
        stem={noteStem}
        onClose={() => setNoteRunId(null)}
        onSaved={() => setNoteRunId(null)}
      />
    </div>
  );
}

// ===== 单条聊天气泡渲染 =====
function ChatBubble({
  msg: m,
  meta,
  timingOn,
  seconds,
}: {
  msg: ChatMsg;
  meta: QuestionMeta | null;
  timingOn: boolean;
  seconds: number;
}) {
  // 思考中 / 生成中：streaming 且还没出字
  const isThinking = m.streaming && !m.text;
  const thinkingText = m.type === 'stem' ? '正在生成题目…' : '思考中…';

  const rowCls = isThinking
    ? `chat-row chat-row-${m.role} chat-row-loading`
    : `chat-row chat-row-${m.role}`;
  const bubbleCls =
    `chat-bubble chat-bubble-${m.role}` +
    (m.type === 'stem' ? ' is-stem' : '') +
    (m.type === 'chat' && m.role === 'ai' && !isThinking ? ' is-tutor' : '') +
    (isThinking ? ' is-loading' : '');

  return (
    <div className={rowCls}>
      {m.role === 'ai' && (
        <div className="chat-avatar chat-avatar-ai"><span>AI</span></div>
      )}
      <div className={bubbleCls}>
        {/* 题干 meta 信息（probe type / runId / 计时）*/}
        {m.type === 'stem' && meta && (
          <div className="chat-stem-meta">
            <Tag>{PROBE_LABEL[meta.probeType] ?? meta.probeType}</Tag>
            <span className="eyebrow">run #{meta.runId}</span>
            {timingOn && (
              <span className="timer-chip">
                <Timer size={14} strokeWidth={1.8} /> {fmt(seconds)}
              </span>
            )}
          </div>
        )}
        {isThinking ? (
          <>
            <span className="spinner-sm" /> {thinkingText}
          </>
        ) : m.type === 'stem' ? (
          // 题干不走 tutor-text（避免"讲解 ·"前缀）；思考过程流式展示（默认展开，markdown）
          <>
            {m.reasoning && (
              <details className="reasoning-panel" open>
                <summary>AI 思考过程</summary>
                <div className="reasoning-text"><Markdown>{m.reasoning}</Markdown></div>
              </details>
            )}
            <Markdown>{m.text}</Markdown>
            {m.streaming && <span className="tutor-caret" aria-hidden />}
          </>
        ) : m.role === 'ai' ? (
          // AI 对话回复走 tutor-text 样式；思考过程流式展示（默认展开，markdown），正文保持干净。
          // revealed=true（答案已揭示）时在气泡顶部渲染「参考答案」分隔线。
          <>
            {m.revealed && (
              <div className="chat-reveal-divider">
                <span>参考答案 · 此后的回答不再计入评分</span>
              </div>
            )}
            <div className="tutor-text">
            {m.reasoning && (
              <details className="reasoning-panel" open>
                <summary>AI 思考过程</summary>
                <div className="reasoning-text"><Markdown>{m.reasoning}</Markdown></div>
              </details>
            )}
            <Markdown>{m.text}</Markdown>
            {m.streaming && <span className="tutor-caret" aria-hidden />}
          </div>
          {m.paused && (
            <div className="chat-paused-note">⏸ 已暂停：未答完的回复未保存，可继续提问</div>
          )}
          </>
        ) : (
          // 用户自己的消息：Markdown 渲染（与 AI 同款），贴的代码自动高亮；图片原样展示
          <div className="me-text">
            {m.images && m.images.length > 0 && (
              <div className="chat-img-row">
                {m.images.map((src, i) => <img key={i} src={src} alt={`消息图片 ${i + 1}`} loading="lazy" />)}
              </div>
            )}
            <Markdown>{m.text}</Markdown>
          </div>
        )}
      </div>
      {m.role === 'me' && (
        <div className="chat-avatar chat-avatar-me"><span>我</span></div>
      )}
    </div>
  );
}

// —— 应用内确认弹窗（Electron 不支持 window.confirm/prompt，必须用组件弹窗）——
function ConfirmDialog({
  title,
  message,
  confirmText = '确定',
  danger = false,
  busy = false,
  onConfirm,
  onClose,
}: {
  title: string;
  message: string;
  confirmText?: string;
  danger?: boolean;
  busy?: boolean;
  onConfirm: () => void;
  onClose: () => void;
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  return (
    <div className="app-modal-backdrop" onClick={onClose}>
      <div className="app-modal" role="dialog" aria-modal="true" aria-label={title} onClick={(e) => e.stopPropagation()}>
        <h3 className="app-modal-title">{title}</h3>
        <p className="app-modal-message">{message}</p>
        <div className="app-modal-actions">
          <Button variant="ghost" onClick={onClose} disabled={busy}>取消</Button>
          <Button variant={danger ? 'danger' : 'primary'} onClick={onConfirm} disabled={busy}>
            {busy ? '处理中…' : confirmText}
          </Button>
        </div>
      </div>
    </div>
  );
}

// —— 应用内输入弹窗（Electron 不支持 window.prompt）——
function PromptDialog({
  title,
  placeholder,
  initial = '',
  submitText = '确定',
  onSubmit,
  onClose,
}: {
  title: string;
  placeholder?: string;
  initial?: string;
  submitText?: string;
  onSubmit: (value: string) => Promise<void>;
  onClose: () => void;
}) {
  const [value, setValue] = useState(initial);
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    inputRef.current?.focus();
  }, []);

  const submit = async () => {
    const v = value.trim();
    if (!v) { setErr('不能为空'); return; }
    setBusy(true);
    setErr('');
    try {
      await onSubmit(v);
      onClose();
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : '操作失败');
      setBusy(false);
    }
  };

  return (
    <div className="app-modal-backdrop" onClick={onClose}>
      <div className="app-modal" role="dialog" aria-modal="true" aria-label={title} onClick={(e) => e.stopPropagation()}>
        <h3 className="app-modal-title">{title}</h3>
        {err && <div className="banner info">{err}</div>}
        <input
          ref={inputRef}
          className="app-modal-input"
          value={value}
          placeholder={placeholder}
          disabled={busy}
          onChange={(e) => setValue(e.target.value)}
          onKeyDown={(e) => {
            e.stopPropagation();
            if (e.key === 'Enter' && !busy) { e.preventDefault(); void submit(); }
            if (e.key === 'Escape') onClose();
          }}
        />
        <div className="app-modal-actions">
          <Button variant="ghost" onClick={onClose} disabled={busy}>取消</Button>
          <Button onClick={submit} disabled={busy || !value.trim()}>
            {busy ? '处理中…' : submitText}
          </Button>
        </div>
      </div>
    </div>
  );
}
