import { useCallback, useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { Timer, NotebookPen, Compass, ChevronRight, ArrowLeft } from 'lucide-react';
import { drill, chatStream, lessonStream, studyPlan, type TutorStream } from '../api/drill';
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
  revealed?: boolean; // 该 AI 回复是「参考答案」（答案已揭示，评分只取揭示之前的回答）
}

// —— 一次练习会话的上下文：决定「下一题」去哪抽 ——
type SessionCtx =
  | { kind: 'free' }
  | { kind: 'concept'; conceptId: number }
  | { kind: 'plan'; planId: number; mode: 'continue' | 'review' | 'layer'; layer?: number }
  | { kind: 'workflow'; planId: number }
  | { kind: 'assessment'; planId: number; mode: 'concept-assessment' | 'level-assessment'; layer: number; conceptId?: number }
  | { kind: 'task' } // 今日任务：做完自动接下一道预生成题
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
      if (turn.rawAnswer) msgs.push({ id: nextMsgId(), role: 'me', text: turn.rawAnswer, type: 'chat' });
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
    if (turn.rawAnswer) msgs.push({ id: nextMsgId(), role: 'me', text: turn.rawAnswer, type: 'chat' });
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
  // 聊天滚动容器
  const threadRef = useRef<HTMLDivElement>(null);

  // —— 视图状态机：home(选方向) / teach(先教后考) / learn(做题) ——
  const [view, setView] = useState<'home' | 'teach' | 'learn'>('home');
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

  // —— 自动滚到底（消息变化时）——
  useEffect(() => {
    if (threadRef.current) {
      threadRef.current.scrollTop = threadRef.current.scrollHeight;
    }
  }, [messages]);

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
      planId?: number; planMode?: 'continue' | 'review' | 'layer' | 'workflow'; layer?: number;
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
            setView('learn');
            navigate('/drill', { replace: true });
          } else {
            setView('learn'); // 全部已判分 → browse 页展示历史 + 继续对话
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
    setView('learn');
    navigate('/drill', { replace: true });

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

  // 播放某个子知识点的讲解（SSE 流式）
  const playSubLesson = (conceptId: number, subPoint: string) => {
    setView('teach');
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
    );
  };

  // 进入「先教后考」：拉子知识点清单
  const enterTeach = async (conceptId: number) => {
    if (sseRef.current) { sseRef.current.cancel(); sseRef.current = null; }
    if (typewriterRef.current) { clearInterval(typewriterRef.current); typewriterRef.current = null; }
    setView('teach');
    setOutlineBusy(true);
    setErr('');
    setTeach({ conceptId, name: '', topic: '', subPoints: [], curIdx: -1, done: [] });
    try {
      const o = await drill.outline(conceptId);
      setTeach({
        conceptId,
        name: o.name,
        topic: o.topic,
        subPoints: o.subPoints,
        curIdx: -1,
        done: o.completedSubPoints ?? [],
      });
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : '拆解失败');
      // 拆解失败降级：直接出题（无讲解）
      setTeach(null);
      directStart(conceptId);
    } finally {
      setOutlineBusy(false);
    }
  };

  const startByConcept = (conceptId: number) => {
    if (teachFirst) enterTeach(conceptId);
    else directStart(conceptId);
  };

  // 点某个子知识点：讲它
  const openSubPoint = (idx: number) => {
    if (!teach) return;
    setTeach({ ...teach, curIdx: idx });
    playSubLesson(teach.conceptId, teach.subPoints[idx]);
  };

  /** 从统一工作流直接进入指定的下一个子知识点，不再先展示一整棵重复的概念选择树。 */
  const enterTeachAt = async (conceptId: number, subPoint: string, planId?: number, taskId?: number) => {
    if (sseRef.current) { sseRef.current.cancel(); sseRef.current = null; }
    setView('teach');
    setOutlineBusy(true);
    setErr('');
    try {
      const o = await drill.outline(conceptId);
      const idx = Math.max(0, o.subPoints.indexOf(subPoint));
      const next = {
        conceptId,
        name: o.name,
        topic: o.topic,
        subPoints: o.subPoints,
        curIdx: idx,
        done: o.completedSubPoints ?? [],
        planId,
        taskId,
      };
      setTeach(next);
      playSubLesson(conceptId, o.subPoints[idx]);
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : '读取学习进度失败');
      setView('home');
    } finally {
      setOutlineBusy(false);
    }
  };

  /** 后端决定唯一下一步：L1→L5、子点教学练习、知识点检测、层级检测。 */
  const startWorkflow = async (planId: number) => {
    setErr('');
    try {
      const next = await drill.learningNext(planId);
      if (next.stepType === 'SUB_POINT' && next.conceptId != null && next.subPoint) {
        await enterTeachAt(next.conceptId, next.subPoint, planId);
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
        setView('home');
      }
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : '无法读取下一学习任务');
      setView('home');
    }
  };

  // 讲完当前子点，开始做题（限定到该子点）
  const startSubQuiz = () => {
    if (!teach || teach.curIdx < 0) return;
    const t = teach;
    const subPoint = t.subPoints[t.curIdx];
    startQuestion(
      () => drill.start(t.conceptId, subPoint),
      { kind: 'teach', conceptId: t.conceptId, subIndex: t.curIdx, planId: t.planId, taskId: t.taskId },
    );
  };

  // 跳过讲解，直接对这个概念出题
  const skipTeach = () => {
    if (!teach) return;
    directStart(teach.conceptId);
  };

  const toggleTeachFirst = (val: boolean) => {
    setTeachFirst(val);
    try { localStorage.setItem(TEACH_FIRST_KEY, val ? '1' : '0'); } catch { /* ignore */ }
  };

  const startByPlan = (planId: number, mode: 'continue' | 'review' | 'layer', layer?: number) =>
    startQuestion(() => drill.startPlan(planId, mode, layer), { kind: 'plan', planId, mode, layer });

  // 连续今日任务：复习直接答题；新学任务由页面先进入该知识点的下一个子知识点教学。
  const startByTask = async (taskId: number) => {
    try {
      const task = (await drill.today()).find((t) => t.id === taskId);
      if (task?.kind === 'NEW') {
        const outline = await drill.outline(task.conceptId);
        const nextSub = outline.subPoints.find((s) => !outline.completedSubPoints.includes(s));
        if (nextSub) {
          await enterTeachAt(task.conceptId, nextSub, task.planId ?? undefined, taskId);
          return;
        }
      }
    } catch { /* 读取失败降级为原有预生成题 */ }
    startQuestion(() => drill.startTask(taskId), { kind: 'task' });
  };

  // ===== 对话 SSE：用户发消息（作答或追问）→ 「思考中…」→ AI 逐 token 回复 → done =====
  // reveal=true 为「看答案」：不需要输入内容，直接向 AI 索要完整答案；服务端会记录
  // 答案揭示边界，之后的回答不再计入评分。
  const sendAnswer = (reveal = false) => {
    if (!meta || phase !== 'chatting') return;
    const userText = reveal ? '我想直接看答案' : input.trim();
    if (!reveal && !userText) return;
    setInput('');

    const userMsgId = nextMsgId();
    const aiMsgId = nextMsgId();

    // 乐观上屏：用户消息 + AI 占位（streaming=true, text='' → 显示"思考中…"）
    setMessages((prev) => [
      ...prev,
      { id: userMsgId, role: 'me', text: userText, type: 'chat' },
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
          setGate(message || '已有未完成的作答');
        } else {
          setErr(message || '回复失败');
        }
      },
      () => {
        // event:reveal —— 服务端已揭示答案：标记该 AI 回复为「参考答案」，
        // 让用户知道从此处开始是参考答案、此后的回答不再计入评分。
        setMessages((prev) => prev.map((mm) =>
          mm.id === aiMsgId ? { ...mm, revealed: true } : mm,
        ));
      },
    );
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
          // 还有下一个子点：讲它
          setTeach({ ...teach, curIdx: nextIdx, done });
          playSubLesson(teach.conceptId, teach.subPoints[nextIdx]);
        } else if (ctx.planId != null) {
          // 当前大知识点的全部子点已处理，交回工作流决定重练未通过子点或开始综合检测。
          startWorkflow(ctx.planId);
        } else {
          setTeach({ ...teach, curIdx: -1, done });
          setView('teach');
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
    setView('home');
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
    setView('learn');
    navigate('/drill', { replace: true });
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
          <button className="head-back" onClick={goHome}>
            <Compass size={14} strokeWidth={1.6} /> 换个方向
          </button>
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
              <p className="teach-hint">逐个点击：先听讲解、再做题，直到全部学完。</p>
              <ul className="teach-outline">
                {t.subPoints.map((sp, i) => {
                  const isDone = t.done.includes(sp);
                  return (
                    <li key={`${sp}-${i}`}>
                      <button
                        className={'teach-sub' + (isDone ? ' is-done' : '')}
                        onClick={() => openSubPoint(i)}
                      >
                        <span className="teach-sub-num">{i + 1}</span>
                        <span className="teach-sub-name">{sp}</span>
                        {isDone && <span className="teach-sub-check">✓</span>}
                      </button>
                    </li>
                  );
                })}
              </ul>
              <div className="teach-foot">
                <Button variant="ghost" onClick={skipTeach}>跳过讲解，直接做题</Button>
              </div>
            </div>
          ) : t && t.curIdx >= 0 ? (
            // 讲解页：讲当前子知识点
            <div className="card teach-card">
              <div className="teach-lesson-head">
                <button className="teach-back" onClick={() => t && setTeach({ ...t, curIdx: -1 })}>
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
                <Button variant="ghost" onClick={() => t && setTeach({ ...t, curIdx: -1 })}>
                  返回清单
                </Button>
                <Button onClick={startSubQuiz} disabled={lessonBusy}>
                  开始做题 <ChevronRight size={16} strokeWidth={1.6} />
                </Button>
              </div>
            </div>
          ) : null}
        </div>
      </div>
    );
  }

  // ===== Learn（聊天式 SSE 练习）=====
  const canSend = phase === 'chatting' && input.trim().length > 0;
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
        <button className="head-back" onClick={goHome}>
          <Compass size={14} strokeWidth={1.6} /> 换个方向
        </button>
      </header>

      {err && <div className="banner info">{err}</div>}

      <div className="chat-panel">
        {/* 聊天线程：题干(AI) → 用户答 → AI回复 → …（不含评分）*/}
        <div className="chat-thread card drill-conv" ref={threadRef}>
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
              <textarea
                className="chat-input-textarea"
                placeholder={
                  phase === 'generating'
                    ? '题目生成中…'
                    : resumedGraded
                      ? '向 AI 提问，继续聊这道题（已判分，不会重新评分）。'
                      : '先回答主问题；AI 会判断你是否理解，再视情况逐条追问（最多 4 个小问，也可能不追问）。想直接看答案可点「看答案」。'
                }
                value={input}
                disabled={phase === 'generating'}
                onChange={(e) => setInput(e.target.value)}
                onCompositionStart={() => { composingRef.current = true; }}
                onCompositionEnd={() => { composingRef.current = false; }}
                onKeyDown={(e) => {
                  if (composingRef.current || e.nativeEvent.isComposing || e.keyCode === 229) return;
                  if (e.key === 'Enter' && !e.shiftKey) {
                    e.preventDefault();
                    sendAnswer(false);
                  }
                }}
                rows={2}
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
                      disabled={phase !== 'chatting'}
                    >
                      结束并评分
                    </Button>
                  )}
                  {phase === 'chatting' && !resumedGraded && (
                    <Button
                      variant="ghost"
                      onClick={() => sendAnswer(true)}
                      title="直接看参考答案（此后的回答不再计入评分）"
                    >
                      看答案
                    </Button>
                  )}
                  <Button onClick={() => sendAnswer(false)} disabled={!canSend}>
                    {phase === 'generating' ? '等待题目' : '发送'}
                  </Button>
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
          </>
        ) : (
          // 用户自己的消息：原样展示、保留换行，不做 markdown 解析
          // （避免被套上 tutor-text 的"讲解 ·"前缀与 markdown 处理，产生多余空行/格式错乱）
          <div className="me-text">{m.text}</div>
        )}
      </div>
      {m.role === 'me' && (
        <div className="chat-avatar chat-avatar-me"><span>我</span></div>
      )}
    </div>
  );
}
