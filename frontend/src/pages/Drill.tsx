import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { Timer, NotebookPen, Compass, ChevronRight, ArrowLeft, RefreshCw, Target, Plus, X, MessageCircle, ImagePlus, Code2 } from 'lucide-react';
import CodeMirror from '@uiw/react-codemirror';
import { markdown, markdownLanguage } from '@codemirror/lang-markdown';
import { languages } from '@codemirror/language-data';
import { keymap, EditorView } from '@codemirror/view';
import { drill, aiSettings, chatStream, lessonChatStream, lessonStream, studyPlan, type TutorStream } from '../api/drill';
import { Button, Tag } from '../components/ui';
import { NoteDialog } from '../components/NoteDialog';
import { ApiError } from '../api/client';
import { PROBE_LABEL } from '../lib/labels';
import type { QuestionView, QuestionMeta, GradeView, PlanView, ConversationView, LessonQaMessageView } from '../api/types';
import { ConversationStream, VerdictPanel } from '../components/ConversationStream';
import { Markdown } from '../components/Markdown';
import { Plans } from './Plans';
import './Drill.css';

function fmt(sec: number): string {
  const m = Math.floor(sec / 60);
  const s = sec % 60;
  return `${m}:${s.toString().padStart(2, '0')}`;
}

// —— 思考过程打字机：即使模型一次吐一大段，也逐字揭示，视觉上像流式 ——
function useTypewriter(target: string, active: boolean, charsPerSec = 320): string {
  const [revealed, setRevealed] = useState(0);
  const targetRef = useRef(target);
  targetRef.current = target;
  const activeRef = useRef(active);
  activeRef.current = active;

  // 完成 / 非生成态：直接显示全部，避免停在半句话上
  useEffect(() => {
    if (!active) setRevealed(targetRef.current.length);
  }, [active]);

  // 生成中：逐步揭示，并持续追上新到达的文本
  useEffect(() => {
    if (!active) return;
    const tick = Math.max(1, Math.round(charsPerSec / 20)); // ~50ms 一帧，每帧揭示若干字符
    const id = setInterval(() => {
      setRevealed(prev => {
        if (!activeRef.current) return targetRef.current.length;
        return Math.min(targetRef.current.length, prev + tick);
      });
    }, 50);
    return () => clearInterval(id);
  }, [active, charsPerSec]);

  // 目标变短（切换子点 / 重置）时收敛揭示进度
  useEffect(() => {
    setRevealed(prev => Math.min(prev, targetRef.current.length));
  }, [targetRef.current.length]);

  return target.slice(0, revealed);
}

/** 思考过程正文：打字机式逐字渲染（仍用 Markdown，部分字符也成文）。 */
function ReasoningText({ text, active, speed }: { text: string; active?: boolean; speed?: number }) {
  const shown = useTypewriter(text, active ?? false, speed);
  return <Markdown>{shown}</Markdown>;
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
  // 出题去重锁：防止连续点击时重复打后端生成题
  const genLockRef = useRef(false);
  // 当前活跃 SSE 流引用（卸载 / 换题时 cancel）
  const sseRef = useRef<TutorStream | null>(null);
  // 题干打字机定时器（卸载 / 换题时清理）
  const typewriterRef = useRef<ReturnType<typeof setInterval> | null>(null);
  // 聊天面板底部哨兵：整页滚动时，新消息 / 评分出现后把最新内容与输入框滚进视野
  const endRef = useRef<HTMLDivElement>(null);
  // 是否自动跟随流式内容滚到底：默认跟随；用户向上滚动即暂停跟随，
  // 滚回接近底部时恢复跟随（经典 AI 聊天行为）。
  const followRef = useRef(true);
  // 答疑线程是否处于「跟随底部」：上翻暂停、滚回底部恢复（与练习聊天一致）。
  const qaFollowRef = useRef(true);
  // 本轮是否真实作答过（reveal「看答案」不算作答）：看答案后据此区分「结束并评分」与「放弃下一题」。
  const hasAnsweredRef = useRef(false);

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
  const editorViewRef = useRef<EditorView | null>(null); // CodeMirror 实例（光标定位/插入代码块用）
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

  // 已看答案（reveal）：看答案后不再追问
  const [revealed, setRevealed] = useState(false);

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
  // —— 讲解页答疑（当前用户私有；不判分、不进 run、不反哺讲解正文）——
  const [qaOpen, setQaOpen] = useState(false);                    // 答疑面板是否展开
  const [qaMessages, setQaMessages] = useState<LessonQaMessageView[]>([]);
  const [qaLoading, setQaLoading] = useState(false);              // 拉取历史中
  const [qaQuestion, setQaQuestion] = useState('');               // 输入框
  const [qaBusy, setQaBusy] = useState(false);                    // AI 回答中
  const [qaReasoning, setQaReasoning] = useState('');             // 当前 AI 思考过程
  const [qaStreamingId, setQaStreamingId] = useState<number | null>(null); // 正在流式回复的临时气泡 id
  const [qaSelecting, setQaSelecting] = useState(false);          // 是否处于「多选删除」模式
  const [qaSelected, setQaSelected] = useState<Set<number>>(new Set());
  const [qaAnchor, setQaAnchor] = useState<string | null>(null);   // 选中讲解片段 → 作为提问上下文
  const qaSseRef = useRef<TutorStream | null>(null);
  const qaThreadRef = useRef<HTMLDivElement>(null);
  const qaSendRef = useRef<(conceptId: number, subPoint: string, anchor: string | null) => void>(() => {});
  const [teachFirst, setTeachFirst] = useState<boolean>(() => {
    try { return localStorage.getItem(TEACH_FIRST_KEY) !== '0'; } catch { return true; }
  });

  // —— 视图由路由派生：/drill=home，/drill/teach/:cid(/:subIdx)=先教后考，/drill/learn=做题 ——
  // 子路由让浏览器前进/后退与「返回」按钮直接走真实历史，不再用内部状态栈。
  // 从子知识点进入的练习问答用嵌套路由 /drill/teach/:cid/:subIdx/learn，返回可回到该子点讲解页。
  const pathSegs = location.pathname.split('/').filter(Boolean); // ['drill', ...]
  const routeTeachCid = pathSegs[1] === 'teach' ? Number(pathSegs[2]) : undefined;
  const routeSubIdx = pathSegs[1] === 'teach' && pathSegs[3] != null ? Number(pathSegs[3]) : undefined;
  // 子点嵌套练习：/drill/teach/:cid/:subIdx/learn —— 做题但保留子点上下文（返回回讲解页）
  const subLearn = pathSegs[1] === 'teach' && pathSegs[4] === 'learn';
  const subLearnCid = subLearn ? routeTeachCid : undefined;
  const subLearnIdx = subLearn ? routeSubIdx : undefined;
  const view: 'home' | 'teach' | 'learn' =
    subLearn ? 'learn' : pathSegs[1] === 'teach' ? 'teach' : pathSegs[1] === 'learn' ? 'learn' : 'home';
  // teach 路由可携带工作流/任务上下文（?plan=&task=），进入做题时保持链式推进
  const planQ = Number(new URLSearchParams(location.search).get('plan')) || undefined;
  const taskQ = Number(new URLSearchParams(location.search).get('task')) || undefined;

  // 「返回」按钮：有历史就后退（鼠标后退键同效），直达场景回学习计划首页。
  // 从子知识点进入的练习（/drill/teach/:cid/:subIdx/learn）→ 回该子点讲解页
  const backOrHome = () => {
    if (subLearn && subLearnCid != null && subLearnIdx != null) {
      navigate(`/drill/teach/${subLearnCid}/${subLearnIdx}`);
      return;
    }
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
    // 离开 teach 视图：清掉加载标记，下次回来重拉（普通离开）。
    // 进入子点嵌套练习（/drill/teach/:cid/:subIdx/learn）时保留 teachRouteRef：
    // 返回讲解页时不重复播放（讲解内容仍在页面状态里），只保留 ref 指向当前子点。
    if (view !== 'teach') {
      if (!subLearn) { teachRouteRef.current = null; resetQaPanel(); return; }
      return;   // subLearn：保留 teach 状态，返回时不重放讲解
    }
    const cid = routeTeachCid;
    if (cid == null || Number.isNaN(cid)) { navigate('/drill', { replace: true }); return; }
    const sub = routeSubIdx ?? -1;

    if (!teach || teach.conceptId !== cid) {
      // 首次进入或切换知识点：拉子知识点清单（懒生成并缓存于后端）
      teachRouteRef.current = { cid, sub };
      if (sseRef.current) { sseRef.current.cancel(); sseRef.current = null; }
      if (typewriterRef.current) { clearInterval(typewriterRef.current); typewriterRef.current = null; }
      resetQaPanel();
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
          // 直达某个子知识点（如练习首页点子知识点 / 工作流进入下一子点）：
          // 首次进入也要自动播放该子点讲解，否则会停在空白讲解页。
          if (sub >= 0 && sub < o.subPoints.length) {
            playSubLesson(cid, o.subPoints[sub]);
          }
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
      resetQaPanel();
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

  // —— 监听滚动容器：用户在底部附近→保持跟随；向上滚动→暂停跟随；滚回底部→恢复跟随 ——
  useEffect(() => {
    if (view !== 'learn') return;
    const scroller = document.querySelector<HTMLElement>('.main');
    if (!scroller) return;
    const onScroll = () => {
      // 距底部 < 80px 视为「在底部」，恢复自动跟随；否则用户主动上翻，暂停跟随
      const nearBottom =
        scroller.scrollHeight - scroller.scrollTop - scroller.clientHeight < 80;
      followRef.current = nearBottom;
    };
    scroller.addEventListener('scroll', onScroll, { passive: true });
    return () => scroller.removeEventListener('scroll', onScroll);
  }, [view]);

  // —— 新消息 / 评分出现时：仅当处于「跟随」状态才把聊天面板底部滚进视野 ——
  useEffect(() => {
    if (!followRef.current) return;
    const scroller = document.querySelector<HTMLElement>('.main');
    if (scroller) {
      scroller.scrollTop = scroller.scrollHeight;
    } else {
      endRef.current?.scrollIntoView({ block: 'end' });
    }
  }, [messages, grade]);

  // —— 卸载时关 SSE 流 + 清打字机 ——
  useEffect(() => () => {
    if (sseRef.current) sseRef.current.cancel();
    if (qaSseRef.current) qaSseRef.current.cancel();
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
    setRevealed(false);
    setPhase('generating');
    setBrowseQid(null);
    // 新题目：恢复自动跟随、重置作答标记，避免残留上一题的状态
    followRef.current = true;
    hasAnsweredRef.current = false;
    // 进入做题页：从知识点页/首页进入 → 压历史（返回可回到来源页）；
    // 已是做题页（下一题）→ replace，避免每一题都堆积一条历史
    // 子知识点练习走嵌套路由 /drill/teach/:cid/:subIdx/learn：返回可回到该子点讲解页
    const learnPath = sessionCtx.kind === 'teach'
      ? `/drill/teach/${sessionCtx.conceptId}/${sessionCtx.subIndex}/learn`
      : '/drill/learn';
    if (view === 'learn') navigate(learnPath, { replace: true });
    else navigate(learnPath);

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

  // —— 讲解页答疑：拉取历史 / 提问 / 删除 ——
  const loadLessonQa = useCallback(async (conceptId: number, subPoint: string) => {
    if (!conceptId || !subPoint) return;
    setQaLoading(true);
    try {
      const list = await drill.lessonQa(conceptId, subPoint);
      setQaMessages(list);
    } catch (e) {
      setQaMessages([]);
    } finally {
      setQaLoading(false);
    }
  }, []);

  // 关闭答疑面板 / 切走子点 / 切换知识点时清理流与选择态
  const resetQaPanel = useCallback(() => {
    if (qaSseRef.current) { qaSseRef.current.cancel(); qaSseRef.current = null; }
    setQaOpen(false);
    setQaMessages([]);
    setQaQuestion('');
    setQaReasoning('');
    setQaAnchor(null);
    setQaBusy(false);
    setQaStreamingId(null);
    setQaSelecting(false);
    setQaSelected(new Set());
    qaFollowRef.current = true;
  }, []);

  const openLessonQa = useCallback(async (conceptId: number, subPoint: string) => {
    setQaOpen(true);
    qaFollowRef.current = true;
    setErr('');
    await loadLessonQa(conceptId, subPoint);
  }, [loadLessonQa]);

  const sendLessonQa = (conceptId: number, subPoint: string, anchor: string | null) => {
    const q = qaQuestion.trim();
    if (!q || qaBusy) return;
    setQaBusy(true);
    setQaReasoning('');
    setErr('');
    // 新提问：回到底部跟随（与练习聊天一致）
    qaFollowRef.current = true;

    // 临时 user 气泡（未持久化 id 未知）：用一个负数占位，流式 assistant 用正数 id 兜底
    const tempUser: LessonQaMessageView = { id: -Date.now(), role: 'user', text: q, anchor, createdAt: '' };
    const tempAi: LessonQaMessageView = { id: -Date.now() - 1, role: 'assistant', text: '', anchor: null, createdAt: '' };
    setQaMessages((prev) => [...prev, tempUser, tempAi]);
    setQaStreamingId(tempAi.id);
    setQaQuestion('');
    setQaAnchor(null);
    setQaSelecting(false);
    setQaSelected(new Set());
    qaSseRef.current = lessonChatStream(
      conceptId,
      subPoint,
      q,
      anchor,
      (token) => {
        setQaMessages((prev) => prev.map((m) =>
          m.id === tempAi.id ? { ...m, text: m.text + token } : m));
      },
      (reasoning) => setQaReasoning((prev) => prev + reasoning),
      (fullText) => {
        // done 事件带回完整回答：用它兜底覆盖（修复偶发末尾截断），再刷新历史拿到持久化 id
        setQaMessages((prev) => prev.map((m) =>
          m.id === tempAi.id ? { ...m, text: fullText ?? m.text } : m));
        qaSseRef.current = null;
        setQaBusy(false);
        setQaStreamingId(null);
        void loadLessonQa(conceptId, subPoint);
      },
      (_status, msg) => {
        qaSseRef.current = null;
        setQaBusy(false);
        setQaStreamingId(null);
        setErr(msg || '答疑失败');
        // 回答失败：把临时气泡移除，只留提问（提问已由后端持久化，刷新后可看到）
        setQaMessages((prev) => prev.filter((m) => m.id !== tempAi.id));
        void loadLessonQa(conceptId, subPoint);
      },
    );
  };

  // 答疑 CodeMirror keymap 需要「最新」的 sendLessonQa 与当前子点上下文
  useEffect(() => {
    qaSendRef.current = sendLessonQa;
  });

  const toggleQaSelect = (id: number) => {
    setQaSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  };

  const deleteSelectedLessonQa = async (conceptId: number, subPoint: string) => {
    const ids = [...qaSelected];
    if (ids.length === 0) return;
    setPendingConfirm({
      title: '删除答疑记录',
      message: `确定删除选中的 ${ids.length} 条答疑记录吗？删除后不可恢复。`,
      confirmText: '删除',
      danger: true,
      action: async () => {
        try {
          await drill.deleteLessonQa(conceptId, subPoint, ids);
          setQaMessages((prev) => prev.filter((m) => !ids.includes(m.id)));
          setQaSelecting(false);
          setQaSelected(new Set());
          setErr('');
        } catch (e) {
          setErr(`删除失败：${e instanceof Error ? e.message : String(e)}`);
        }
      },
    });
  };

  // —— 答疑线程滚动：监听答疑容器，上翻暂停跟随、滚回底部恢复（与练习聊天一致）——
  useEffect(() => {
    if (!qaOpen) return;
    const scroller = qaThreadRef.current;
    if (!scroller) return;
    const onScroll = () => {
      const nearBottom =
        scroller.scrollHeight - scroller.scrollTop - scroller.clientHeight < 80;
      qaFollowRef.current = nearBottom;
    };
    scroller.addEventListener('scroll', onScroll, { passive: true });
    return () => scroller.removeEventListener('scroll', onScroll);
  }, [qaOpen]);

  // 新消息 / 流式更新时：仅当处于「跟随」状态才把答疑线程底部滚进视野
  useEffect(() => {
    if (!qaOpen || !qaFollowRef.current) return;
    const scroller = qaThreadRef.current;
    if (scroller) scroller.scrollTop = scroller.scrollHeight;
  }, [qaMessages, qaOpen, qaStreamingId]);

  // 进入「先教后考」：跳子路由，由路由 effect 拉子知识点清单
  const enterTeach = (conceptId: number) => {
    navigate(`/drill/teach/${conceptId}`);
  };

  const startByConcept = (conceptId: number) => {
    if (teachFirst) enterTeach(conceptId);
    else directStart(conceptId);
  };

  // 练习首页直接点某个子知识点：先进入该子点讲解页（讲解页有「开始做题」），
  // 做题走嵌套路由 /drill/teach/:cid/:subIdx/learn，返回自然回到该子点讲解页。
  const pickSubPoint = (conceptId: number, _subPoint: string, subIndex: number, planId?: number) => {
    enterTeachAt(conceptId, planId, undefined, subIndex);
  };

  // 子知识点直通的「下一题」：从 subIndex 之后找下一个未完成的子点继续练（跳过已通过的）。
  // 该知识点子点全部处理完 → 有方向交回工作流（综合检测等），否则退回按整个知识点出题。
  const nextSubPointDirect = async (conceptId: number, subIndex: number, planId?: number) => {
    try {
      const o = await drill.outline(conceptId);
      const done = new Set(o.completedSubPoints ?? []);
      const nextIdx = o.subPoints.findIndex((s, i) => i > subIndex && !done.has(s));
      if (nextIdx >= 0) {
        startQuestion(
          () => drill.start(conceptId, o.subPoints[nextIdx]),
          { kind: 'teach', conceptId, subIndex: nextIdx, planId },
        );
      } else if (planId != null) {
        startWorkflow(planId);
      } else {
        directStart(conceptId);
      }
    } catch {
      directStart(conceptId);
    }
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

  /** 输入框工具栏：插入 ```语言 代码块模板，光标落到块内。 */
  const insertCodeBlock = () => {
    const view = editorViewRef.current;
    const block = '```语言\n\n```';
    const cursorOffset = '```语言\n'.length;
    if (view) {
      const { from } = view.state.selection.main;
      // 在光标处插入代码块模板，并把光标放到块内（`` 与 ``` 之间的大写“语言”占位之后）
      view.dispatch({ changes: { from, insert: block }, selection: { anchor: from + cursorOffset } });
      view.focus();
    } else {
      // 兜底：编辑器未挂载时退化为整段追加
      setInput((prev) => prev + block);
    }
  };

  // ===== 对话 SSE：用户发消息（作答或追问）→ 「思考中…」→ AI 逐 token 回复 → done =====
  // reveal=true 为「看答案」：不需要输入内容，直接向 AI 索要完整答案；服务端会记录
  // 答案揭示边界，之后的回答不再计入评分。
  // 评分权交给用户：AI 只负责追问/讲解（chat），判分由用户点「结束并评分」（finish）触发。
  const sendAnswer = (reveal = false) => {
    // 看答案在对话 / 已评分阶段都可用（已评分看答案 = 揭示参考答案，不再追问）
    // 已评分（graded）时普通发送 = 教学对话（AI 讲解/答疑，不再评分）
    if (!meta || (phase !== 'chatting' && phase !== 'graded')) return;
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

    // 记录用户是否真实作答过（reveal 不算作答）：看答案后用于区分「可评分」与「放弃」。
    if (!reveal && !resumedGraded) hasAnsweredRef.current = true;

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
        // 看答案后不再追问
        setRevealed(true);
      },
      sendingImages,
    );
  };

  // —— CodeMirror 输入框配置 ——
  // keymap / paste handler 里要拿到「最新」的 sendAnswer 与 addImageFiles，
  // 但 extensions 只在挂载时创建一次（避免每次渲染重建导致光标跳动）。
  const sendRef = useRef(sendAnswer);
  const addImagesRef = useRef(addImageFiles);
  useEffect(() => { sendRef.current = sendAnswer; });
  useEffect(() => { addImagesRef.current = addImageFiles; });

  const cmExtensions = useMemo(() => [
    // Markdown 模式：外层按 markdown 高亮；```代码块 内自动按语言 data 里匹配的语言高亮
    markdown({ base: markdownLanguage, codeLanguages: languages }),
    // Ctrl/Cmd+Enter 发送（Enter 本身是换行，写代码不误发）；composition 时不拦截
    keymap.of([{
      key: 'Mod-Enter',
      preventDefault: true,
      run: (view) => {
        if (view.composing) return false;
        sendRef.current(false);
        return true;
      },
    }]),
    // 粘贴截图：剪贴板有图片则直接附加为待发送图片
    EditorView.domEventHandlers({
      paste: (e) => {
        const items = e.clipboardData?.items;
        if (!items) return false;
        const imgs: File[] = [];
        for (const it of Array.from(items)) {
          if (it.type.startsWith('image/')) {
            const f = it.getAsFile();
            if (f) imgs.push(f);
          }
        }
        if (imgs.length > 0) {
          e.preventDefault();
          void addImagesRef.current(imgs);
          return true;
        }
        return false;
      },
    }),
    // 配色：与应用浅色纸面风格一致（继承 app 的 CSS 变量）
    EditorView.theme({
      '&': {
        backgroundColor: 'var(--paper)',
        fontSize: '0.9rem',
      },
      '.cm-scroller': {
        fontFamily: 'var(--font-mono)',
        caretColor: 'var(--cinnabar)',
        lineHeight: '1.6',
      },
      '&.cm-focused': { outline: 'none' },
      '.cm-content': { padding: 'var(--s-2) var(--s-3)' },
      '.cm-cursor, .cm-dropCursor': { borderLeftColor: 'var(--cinnabar)' },
      '&.cm-focused .cm-selectionBackground, .cm-selectionBackground, ::selection': {
        backgroundColor: 'rgba(114, 88, 68, 0.22)',
      },
      '.cm-gutters': {
        backgroundColor: 'transparent',
        color: 'var(--ink-faint)',
        border: 'none',
      },
      '.cm-activeLine': { backgroundColor: 'rgba(114, 88, 68, 0.05)' },
      '.cm-activeLineGutter': { backgroundColor: 'transparent', color: 'var(--cinnabar)' },
      '.cm-selectionMatch': { backgroundColor: 'rgba(114, 88, 68, 0.12)' },
      '.cm-tooltip': {
        backgroundColor: 'var(--paper)',
        border: '1px solid var(--line-strong)',
        borderRadius: 'var(--r-sm)',
        boxShadow: '0 10px 28px rgba(35,43,54,0.16)',
      },
      '.cm-tooltip-autocomplete ul li[aria-selected]': {
        backgroundColor: 'color-mix(in oklch, var(--cinnabar) 18%, transparent)',
        color: 'var(--ink)',
      },
    }, { dark: false }),
  ], []);

  // 答疑输入框扩展：复用主练习的 markdown 高亮 + 配色，但发送键发答疑（Mod-Enter 提问）
  // 发送上下文（conceptId/subPoint/anchor）由答疑输入框渲染时写入 qaCtxRef，keymap 读取最新值
  const qaCtxRef = useRef<{ conceptId: number; subPoint: string } | null>(null);
  const qaAnchorRef = useRef<string | null>(null);
  const qaExtensions = useMemo(() => [
    markdown({ base: markdownLanguage, codeLanguages: languages }),
    keymap.of([{
      key: 'Mod-Enter',
      preventDefault: true,
      run: (view) => {
        if (view.composing) return false;
        const ctx = qaCtxRef.current;
        if (ctx) qaSendRef.current(ctx.conceptId, ctx.subPoint, qaAnchorRef.current);
        return true;
      },
    }]),
  ], []);

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
        if (!teach) {
          // 从练习首页直接点子知识点（无 teach 状态）：练完自动接同知识点下一个未完成的子点
          void nextSubPointDirect(ctx.conceptId, ctx.subIndex, ctx.planId);
          break;
        }
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
  // 浏览模式优先于 home：从问答记录点进已结束的题时，路由仍是 /drill（view=home），
  // 需让 browse 视图先于 home 渲染，否则会退回方向选择页而看不到对话历史。
  if (view === 'home' && !browseMode) {
    return (
      <Plans
        plans={plans}
        onPick={startByConcept}
        onPickSub={pickSubPoint}
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
                {/* 备课中：只在尚无任何推理与正文时显示纯 spinner；一旦有思考流先展示思考过程，
                    否则长思考阶段只会一直“正在备课…”看不到模型在干嘛。 */}
                {lessonBusy && !lessonText && !lessonReasoning ? (
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
                        <div className="reasoning-text"><ReasoningText text={lessonReasoning} active={lessonBusy} /></div>
                      </details>
                    )}
                    <div className="tutor-text" onMouseUp={() => {
                      const sel = window.getSelection();
                      const txt = sel && !sel.isCollapsed ? sel.toString().trim() : '';
                      if (txt && txt.length <= 500) setQaAnchor(txt);
                    }}>
                      {lessonText ? (
                        <Markdown>{lessonText}</Markdown>
                      ) : lessonBusy ? (
                        <span className="spinner-sm" />
                      ) : (
                        '（讲解内容为空）'
                      )}
                      {lessonBusy && <span className="tutor-caret" aria-hidden />}
                    </div>
                  </>
                )}
              </div>

              {/* 讲解页答疑：随时提问，AI 结合讲解回答（仅当前用户私有，可多选删除） */}
              <div className="lesson-qa">
                <div className="lesson-qa-head">
                  <button
                    className="lesson-qa-toggle"
                    onClick={async () => {
                      if (qaOpen) { resetQaPanel(); setQaOpen(false); }
                      else await openLessonQa(t.conceptId, t.subPoints[t.curIdx]);
                    }}
                    title="针对这段讲解随时提问，AI 会结合讲解和你的学习资料回答"
                  >
                    <MessageCircle size={15} strokeWidth={1.8} />
                    {qaOpen ? '收起答疑' : `答疑（${qaMessages.length > 0 ? qaMessages.filter(m => m.role === 'user').length : 0}）`}
                  </button>
                  {qaOpen && qaMessages.length > 0 && !qaBusy && (
                    <div className="lesson-qa-head-actions">
                      {qaSelecting ? (
                        <>
                          <span className="lesson-qa-select-hint">已选 {qaSelected.size} 条</span>
                          <Button variant="ghost" className="btn-sm" onClick={() => { setQaSelecting(false); setQaSelected(new Set()); }}>
                            取消
                          </Button>
                          <Button
                            variant="danger"
                            className="btn-sm"
                            disabled={qaSelected.size === 0}
                            onClick={() => deleteSelectedLessonQa(t.conceptId, t.subPoints[t.curIdx])}
                          >
                            删除
                          </Button>
                        </>
                      ) : (
                        <Button variant="ghost" className="btn-sm" onClick={() => setQaSelecting(true)}>
                          删除记录
                        </Button>
                      )}
                    </div>
                  )}
                </div>

                {qaOpen && (
                  <div className="lesson-qa-body">
                    {qaLoading ? (
                      <div className="chat-row chat-row-ai chat-row-loading">
                        <div className="chat-bubble chat-bubble-ai is-loading">
                          <span className="spinner-sm" /> 读取答疑历史…
                        </div>
                      </div>
                    ) : (
                      <div className="lesson-qa-thread" ref={qaThreadRef}>
                        {qaMessages.length === 0 && !qaBusy ? (
                          <div className="chat-row chat-row-ai">
                            <div className="chat-bubble chat-bubble-ai is-muted">
                              还没有提问。读讲解时有疑惑，可以直接在这里问，或选中讲解中的某句话再问。
                            </div>
                          </div>
                        ) : (
                          qaMessages.map((m) => (
                            <div
                              key={m.id}
                              className={'chat-row ' + (m.role === 'user' ? 'chat-row-me' : 'chat-row-ai') + (qaSelecting ? ' is-qa-select' : '')}
                              onClick={qaSelecting ? () => toggleQaSelect(m.id) : undefined}
                            >
                              {qaSelecting && (
                                <span className={'lesson-qa-check' + (qaSelected.has(m.id) ? ' on' : '')} />
                              )}
                              <div className={'chat-bubble ' + (m.role === 'user' ? 'chat-bubble-me' : 'chat-bubble-ai')}>
                                {m.anchor && m.role === 'user' && (
                                  <div className="lesson-qa-anchor" title="你选中的讲解片段">
                                    “{m.anchor}”
                                  </div>
                                )}
                                {m.role === 'user' ? (
                                  <p className="me-text">{m.text}</p>
                                ) : (
                                  <div className="tutor-text lesson-qa-answer">
                                    <Markdown>{m.text || (qaStreamingId === m.id ? '…' : '')}</Markdown>
                                    {qaStreamingId === m.id && <span className="tutor-caret" aria-hidden />}
                                  </div>
                                )}
                              </div>
                            </div>
                          ))
                        )}
                        {qaReasoning && qaBusy && (
                          <details className="reasoning-panel" open>
                            <summary>AI 思考过程</summary>
                            <div className="reasoning-text"><ReasoningText text={qaReasoning} active={qaBusy} /></div>
                          </details>
                        )}
                      </div>
                    )}

                    <div className="lesson-qa-input">
                      {qaAnchor && (
                        <div className="lesson-qa-anchor-chip">
                          <span className="lesson-qa-anchor-text" title={qaAnchor}>
                            选中了讲解片段：“{qaAnchor}”
                          </span>
                          <button
                            className="lesson-qa-anchor-clear"
                            onClick={() => setQaAnchor(null)}
                            title="取消这段引用"
                          >
                            <X size={13} strokeWidth={2.2} />
                          </button>
                        </div>
                      )}
                      <div className="lesson-qa-input-row">
                        <CodeMirror
                          value={qaQuestion}
                          placeholder={qaAnchor ? '就选中这段话提问（AI 会结合它回答）' : '针对这段讲解提问（如：为什么这里不能用 xxx？）'}
                          height="auto"
                          minHeight="44px"
                          maxHeight="360px"
                          editable={!qaBusy}
                          extensions={qaExtensions}
                          onChange={(val) => setQaQuestion(val)}
                          onUpdate={(vu) => {
                            // 渲染时记录当前答疑上下文，供 keymap（Mod-Enter 提问）读取
                            qaCtxRef.current = { conceptId: t.conceptId, subPoint: t.subPoints[t.curIdx] };
                            qaAnchorRef.current = qaAnchor;
                            if (vu.view.composing) return;
                          }}
                          className="lesson-qa-textarea"
                        />
                        <Button
                          onClick={() => sendLessonQa(t.conceptId, t.subPoints[t.curIdx], qaAnchor)}
                          disabled={qaBusy || !qaQuestion.trim()}
                        >
                          {qaBusy ? '回答中…' : '提问'}
                        </Button>
                      </div>
                    </div>
                  </div>
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
  // AI 正在流式回复中：显示「暂停」而不是发送（回车=换行，Ctrl/⌘+回车=发送）
  const aiStreaming = messages.some((m) => m.streaming);
  // 对话 / 已评分（教学对话）都可发送；发送键 Ctrl/⌘+回车
  const canSend =
    (phase === 'chatting' || phase === 'graded') &&
    input.trim().length > 0 &&
    !aiStreaming;
  // 至少有一轮用户真实作答才能结束评分；已判分 run 上继续的对话不再重新评分，隐藏该按钮。
  const canFinish =
    phase === 'chatting' &&
    hasAnsweredRef.current &&
    !resumedGraded;
  const hasLowGrade = grade != null && grade.rawScore < 60;

  // —— 练习页标题：按考察粒度展示当前知识点 / 子知识点 / L 层级 ——
  const conceptNameOf = (conceptId: number | undefined): string => {
    if (conceptId == null) return '';
    if (teach && teach.conceptId === conceptId && teach.name) return teach.name;
    for (const p of plans) {
      const c = p.concepts.find((x) => x.id === conceptId);
      if (c && c.name) return c.name;
    }
    return '';
  };
  const planTitleOf = (planId: number | undefined): string =>
    planId != null ? (plans.find((p) => p.id === planId)?.title ?? '') : '';

  let headEyebrow = '练习 · LEARN';
  let headTitle = '练习';
  if (ctx?.kind === 'teach' || subLearn) {
    // 子知识点练习（先教后考讲解页进入）：展示「知识点 · 子知识点」
    const subName = teach && teach.curIdx >= 0 ? teach.subPoints[teach.curIdx] ?? '' : '';
    const cName = conceptNameOf(teach?.conceptId ?? subLearnCid);
    headEyebrow = '先教后考 · 子知识点练习';
    headTitle = subName
      ? (cName ? `练习 · ${cName} · ${subName}` : `练习 · ${subName}`)
      : (cName ? `练习 · ${cName}` : '练习 · 子知识点');
  } else if (ctx?.kind === 'scoped' && ctx.scope === 'concept') {
    // 整个知识点练习
    const cName = conceptNameOf(ctx.conceptId);
    headEyebrow = '练习 · 整个知识点';
    headTitle = cName ? `练习 · ${cName}` : '练习 · 知识点';
  } else if (ctx?.kind === 'scoped' && ctx.scope === 'layer') {
    // 整层级练习
    headEyebrow = '练习 · 层级考察';
    headTitle = `练习 · L${ctx.layer ?? 0} 层级`;
  } else if (ctx?.kind === 'concept') {
    // 直接按知识点出题（未开讲解）
    const cName = conceptNameOf(ctx.conceptId);
    headEyebrow = '练习 · 知识点';
    headTitle = cName ? `练习 · ${cName}` : '练习 · 知识点';
  } else if (ctx?.kind === 'plan' && (ctx.mode === 'layer' || ctx.mode === 'layer-practice')) {
    // 按 L 层级练习
    headEyebrow = '练习 · 层级考察';
    headTitle = `练习 · L${ctx.layer ?? 0} 层级`;
  } else if (ctx?.kind === 'plan') {
    // 计划继续 / 复习
    const pTitle = planTitleOf(ctx.planId);
    headEyebrow = '练习 · 学习方向';
    headTitle = pTitle ? `练习 · ${pTitle}` : '练习 · 学习方向';
  } else if (ctx?.kind === 'assessment') {
    if (ctx.mode === 'concept-assessment') {
      const cName = conceptNameOf(ctx.conceptId);
      headEyebrow = '综合检测';
      headTitle = cName ? `综合检测 · ${cName}` : '综合检测 · 知识点';
    } else {
      headEyebrow = '综合检测';
      headTitle = `综合检测 · L${ctx.layer ?? 0} 层级`;
    }
  } else if (ctx?.kind === 'task') {
    headEyebrow = '今日任务';
    headTitle = '今日任务 · 练习';
  }

  return (
    <div className="page chat-page">
      <header className="page-head chat-head">
        <div className="chat-head-title">
          <span className="eyebrow">{headEyebrow}</span>
          <h1>{headTitle}</h1>
        </div>
        <div className="head-actions">
          <button className="head-back" onClick={backOrHome} title={subLearn ? '返回该子知识点讲解页' : '返回上一页（也支持鼠标后退键）'}>
            <ArrowLeft size={14} strokeWidth={1.6} /> {subLearn ? '回讲解页' : '返回'}
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

        {/* 输入区：按 phase 分态（resumedGraded 时隐藏「结束并评分」）
            苏格拉底：chatting=答疑；graded=已判分，保留输入框做教学对话（不评分） */}
        <div className="chat-input card">
          {phase === 'finishing' ? (
            // 评分中：禁用
            <div className="chat-input-foot">
              <span className="chat-input-hint">正在基于整轮对话评分…</span>
              <Button disabled>
                <span className="spinner spinner-inline" /> 评分中
              </Button>
            </div>
          ) : (
            // 对话 / 生成 / 已评分（教学对话）：输入框 + 动作
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
              <CodeMirror
                className="chat-input-cm"
                value={input}
                placeholder={
                  phase === 'generating'
                    ? '题目生成中…'
                    : phase === 'graded' && !resumedGraded
                      ? '已判分：可继续向 AI 提问（教学讲解，不再评分）。回车换行，Ctrl/⌘+回车发送。'
                      : resumedGraded
                        ? '向 AI 提问，继续聊这道题（已判分，不会重新评分）。回车换行，Ctrl/⌘+回车发送。'
                        : '先回答主问题；AI 会判断你是否理解，再视情况逐条追问。答完可点「结束并评分」，想直接看答案可点「看答案」。回车换行，Ctrl/⌘+回车发送。'
                }
                editable={phase !== 'generating'}
                extensions={cmExtensions}
                autoFocus
                onCreateEditor={(view) => { editorViewRef.current = view; }}
                onChange={(val) => setInput(val)}
                minHeight="44px"
                maxHeight="360px"
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
                  {phase === 'graded' && hasLowGrade && (
                    <Button variant="ghost" onClick={openNote}>
                      <NotebookPen size={16} strokeWidth={1.6} /> 写内化笔记
                    </Button>
                  )}
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
                  {phase !== 'generating' && !revealed && (
                    <Button
                      variant="ghost"
                      onClick={() => sendAnswer(true)}
                      disabled={aiStreaming}
                      title="直接看参考答案（看答案后不再追问）"
                    >
                      看答案
                    </Button>
                  )}
                  {phase === 'chatting' && revealed && canFinish && (
                    <Button
                      variant="ghost"
                      onClick={finishAndGrade}
                      disabled={aiStreaming}
                      title="已看答案，按已答内容评分"
                    >
                      结束并评分
                    </Button>
                  )}
                  {phase === 'chatting' && revealed && !hasAnsweredRef.current && (
                    <Button
                      variant="ghost"
                      onClick={async () => {
                        // 未作答即看答案：按 AGAIN 结算闭环，放行下一题
                        if (meta) {
                          try { await drill.abandon(meta.runId); } catch { /* 忽略：仍尝试下一题 */ }
                        }
                        goNext();
                      }}
                      disabled={aiStreaming}
                      title="未作答即看答案，本题不再评分，直接下一题"
                    >
                      下一题
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
                  {phase === 'graded' && (
                    <Button onClick={goNext}>
                      下一题 <ChevronRight size={16} strokeWidth={1.6} />
                    </Button>
                  )}
                </div>
              </div>
            </>
          )}
        </div>

        {/* 评分总结：独立于聊天线程，显示在输入框下方。 */}
        {(grade) && (
          <div className="chat-verdict card">
            <VerdictPanel
              run={{
                runId: grade?.runId ?? meta?.runId ?? 0,
                mode: 'LEARN',
                status: 'GRADED',
                sourceRunId: null,
                rawScore: grade?.rawScore ?? 0,
                grade: grade?.grade ?? 'AGAIN',
                answeredAt: '',
                turns: [],
              }}
              turn={{
                round: 0,
                stem: '',
                rawAnswer: '',
                rawScore: grade?.rawScore ?? 0,
                passed: (grade?.rawScore ?? 0) >= 60,
                byConceptJson: grade?.byConceptJson ?? null,
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
                <div className="reasoning-text"><ReasoningText text={m.reasoning} active={m.streaming} /></div>
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
                <div className="reasoning-text"><ReasoningText text={m.reasoning} active={m.streaming} /></div>
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
