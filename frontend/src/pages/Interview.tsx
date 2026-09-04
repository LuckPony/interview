import { useEffect, useRef, useState } from 'react';
import {
  Mic, Type, FileText, BookOpen, ChevronRight, X,
  CheckCircle2, XCircle, Send, Volume2, Square, Loader2, Timer,
} from 'lucide-react';
import { interviewApi, resumeApi, type InterviewSession, type CurrentQuestion, type InterviewMode } from '../api/interview';
import { studyPlan } from '../api/drill';
import { Button, Card, Badge } from '../components/ui';
import { Markdown } from '../components/Markdown';
import { ApiError } from '../api/client';
import type { PlanView } from '../api/types';
import './Interview.css';

function msg(e: unknown): string {
  if (e instanceof ApiError) return e.message;
  if (e instanceof Error && e.message) return e.message;
  return '操作失败，请重试';
}

const DIFFICULTY_LABEL: Record<string, string> = { JUNIOR: '初级', MIDDLE: '中级', SENIOR: '高级' };
const DIFFICULTY_TIME: Record<string, string> = { JUNIOR: '约 60 分钟', MIDDLE: '约 60 分钟', SENIOR: '约 60 分钟' };

type Phase = 'config' | 'interview' | 'result';

/** 语音识别（Web Speech API，Chrome 免费内置） */
function createRecognizer(onText: (t: string) => void, onEnd: () => void): any {
  const W = window as any;
  const SR = W.SpeechRecognition ?? W.webkitSpeechRecognition;
  if (!SR) return null;
  const rec = new SR();
  rec.lang = 'zh-CN';
  rec.interimResults = true;
  rec.continuous = true;
  let final = '';
  rec.onresult = (e: any) => {
    let interim = '';
    for (let i = e.resultIndex; i < e.results.length; i++) {
      const t = e.results[i][0]?.transcript ?? '';
      if (e.results[i].isFinal) final += t; else interim += t;
    }
    onText((final + interim).trim());
  };
  rec.onend = () => { onEnd(); };
  rec.onerror = () => { onEnd(); };
  return rec;
}

/** AI 发声（浏览器内置 speechSynthesis） */
export function speak(text: string): void {
  try {
    const synth = window.speechSynthesis;
    if (!synth) return;
    synth.cancel();
    const u = new SpeechSynthesisUtterance(text.replace(/[#*`>\-\d.]/g, ' '));
    u.lang = 'zh-CN';
    u.rate = 1;
    const zh = synth.getVoices().find(v => v.lang.toLowerCase().startsWith('zh'));
    if (zh) u.voice = zh;
    synth.speak(u);
  } catch { /* 语音不可用时静默降级 */ }
}

/** 秒 → mm:ss */
function fmtTime(sec: number): string {
  const m = Math.floor(sec / 60);
  const s = Math.max(0, sec % 60);
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
}

/** 进行中面试会话持久化 */
const SESSION_KEY = 'yan.interview.sessionId';

export function Interview() {
  const [phase, setPhase] = useState<Phase>('config');

  // —— 配置态 ——
  const [mode, setMode] = useState<InterviewMode>('TEXT');
  const [plans, setPlans] = useState<PlanView[]>([]);
  const [resumes, setResumes] = useState<Awaited<ReturnType<typeof resumeApi.list>>>([]);
  const [resumeId, setResumeId] = useState<number | null>(null);
  const [planIds, setPlanIds] = useState<number[]>([]);
  const [difficulty, setDifficulty] = useState<'JUNIOR' | 'MIDDLE' | 'SENIOR'>('MIDDLE');
  const [configErr, setConfigErr] = useState('');
  const [creating, setCreating] = useState(false);
  const [uploading, setUploading] = useState(false);

  // —— 面试态 ——
  const [session, setSession] = useState<InterviewSession | null>(null);
  const [question, setQuestion] = useState<CurrentQuestion | null>(null);
  const [answer, setAnswer] = useState('');
  const [busy, setBusy] = useState(false);
  const [voiceOn, setVoiceOn] = useState(false);
  const [recogText, setRecogText] = useState('');
  const [listening, setListening] = useState(false);
  const [left, setLeft] = useState(0);
  const recRef = useRef<any>(null);
  const [err, setErr] = useState('');

  useEffect(() => {
    studyPlan.list().then(setPlans).catch(() => {});
    resumeApi.list().then(setResumes).catch(() => {});
  }, []);

  // 倒计时
  useEffect(() => {
    setLeft(question?.remainingSeconds ?? 0);
    if (!question || question.finished) return;
    const iv = window.setInterval(() => {
      setLeft(prev => Math.max(0, prev - 1));
    }, 1000);
    return () => window.clearInterval(iv);
  }, [question?.sessionId, question?.question, question?.finished]);

  // 恢复进行中的面试
  useEffect(() => {
    const resumeId = new URLSearchParams(window.location.hash.split('?')[1] ?? '').get('resume')
      ?? sessionStorage.getItem(SESSION_KEY);
    if (!resumeId) return;
    let alive = true;
    (async () => {
      try {
        const s = await interviewApi.detail(resumeId);
        if (!alive) return;
        if (s.status === 'COMPLETED') { sessionStorage.removeItem(SESSION_KEY); return; }
        setSession(s);
        setPhase('interview');
        if (s.status === 'PENDING_EVALUATION') {
          // 待评估 → 显示面试结束界面（含"完成评估"按钮），不拉 currentQuestion
          return;
        }
        await refreshQuestion(resumeId);
      } catch { /* 会话不存在/过期，忽略 */ }
    })();
    return () => { alive = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const togglePlan = (id: number) => {
    setPlanIds(prev => prev.includes(id) ? prev.filter(x => x !== id) : [...prev, id]);
  };

  const uploadResume = async (file: File) => {
    setUploading(true);
    setConfigErr('');
    try {
      const r = await resumeApi.upload(file);
      const list = await resumeApi.list();
      setResumes(list);
      setResumeId(r.id ?? list[list.length - 1]?.id ?? null);
    } catch (e) {
      setConfigErr(msg(e));
    } finally {
      setUploading(false);
    }
  };

  const refreshQuestion = async (sessionId: string) => {
    const q = await interviewApi.currentQuestion(sessionId);
    setQuestion(q);
    setAnswer('');
    setRecogText('');
    if (q.question && mode === 'VOICE') {
      const tag = q.followUpIndex > 0 ? `追问：` : '';
      speak(`${tag}${q.question}`);
    }
    return q;
  };

  const startInterview = async () => {
    if (!resumeId && planIds.length === 0) {
      setConfigErr('请上传简历或选择至少一个学习方向，才能开始面试（二选一，可都选）');
      return;
    }
    setConfigErr('');
    setCreating(true);
    try {
      const s = await interviewApi.createSession({ resumeId, planIds, difficulty, mode });
      sessionStorage.setItem(SESSION_KEY, s.id);
      setSession(s);
      setPhase('interview');
      await refreshQuestion(s.id);
    } catch (e) {
      setConfigErr(msg(e));
    } finally {
      setCreating(false);
    }
  };

  const completeNow = async (sid: string) => {
    setBusy(true);
    setErr('');
    try {
      const done = await interviewApi.completeAndEvaluate(sid);
      sessionStorage.removeItem(SESSION_KEY);
      setSession(done);
      setQuestion(null);
      setPhase('result');
    } catch (e) {
      setErr('评估失败：' + msg(e) + '，可稍后重试');
    } finally {
      setBusy(false);
    }
  };

  const submit = async () => {
    if (!session || !question || busy) return;
    const text = (answer || recogText).trim();
    if (!text) { setErr('请先作答再提交'); return; }
    setBusy(true);
    setErr('');
    try {
      const s = await interviewApi.submitAnswer(session.id, question.baseIndex, text);
      setSession(s);
      if (s.status === 'PENDING_EVALUATION' || s.status === 'COMPLETED') {
        // 答题结束 → 待评估（显示面试结束，等用户点评估）
        setQuestion(q => (q ? { ...q, finished: true } : q));
      } else {
        await refreshQuestion(session.id);
      }
    } catch (e) {
      setErr(msg(e));
    } finally {
      setBusy(false);
    }
  };

  const quit = () => {
    if (!window.confirm('退出后计时仍会继续，计时结束将自动结束面试并进入待评估。确定退出吗？')) return;
    if (recRef.current) recRef.current.stop();
    sessionStorage.removeItem(SESSION_KEY);
    setPhase('config');
    setSession(null);
    setQuestion(null);
    setAnswer('');
    setErr('');
  };

  const toggleListening = () => {
    if (listening) {
      recRef.current?.stop();
      setListening(false);
      return;
    }
    setRecogText('');
    const rec = createRecognizer(
      (t) => setRecogText(t),
      () => setListening(false),
    );
    if (!rec) { setErr('当前浏览器不支持语音识别，请使用 Chrome 桌面版'); return; }
    recRef.current = rec;
    setListening(true);
    rec.start();
  };

  const finished = question?.finished || session?.status === 'PENDING_EVALUATION';

  return (
    <div className="page interview-page">
      <header className="page-head">
        <span className="eyebrow">模拟面试 · INTERVIEW</span>
        <h1>模拟面试</h1>
        <p>基于简历与学习方向出题，时长与追问深度由难度决定，结束后 AI 评估打分。</p>
      </header>

      <div className="iv-header-actions">
        <a className="ih-back" href="#/rehearsal/history">查看面试记录</a>
      </div>

      {err && <div className="banner warn">{err}</div>}

      {phase === 'config' && (
        <InterviewConfig
          mode={mode} setMode={setMode}
          plans={plans} planIds={planIds} togglePlan={togglePlan}
          resumes={resumes} resumeId={resumeId} setResumeId={setResumeId}
          difficulty={difficulty} setDifficulty={setDifficulty}
          uploading={uploading} onUpload={uploadResume}
          err={configErr} creating={creating}
          canStart={!!resumeId || planIds.length > 0}
          onStart={startInterview}
        />
      )}

      {phase === 'interview' && session && (
        <InterviewRun
          session={session}
          question={question}
          mode={mode}
          answer={answer}
          setAnswer={setAnswer}
          busy={busy}
          onSubmit={submit}
          onComplete={() => completeNow(session.id)}
          onQuit={quit}
          finished={!!finished}
          left={left}
          listening={listening}
          recogText={recogText}
          voiceOn={voiceOn}
          onToggleVoice={() => setVoiceOn(v => !v)}
          onToggleListening={toggleListening}
        />
      )}

      {phase === 'result' && session && (
        <InterviewResult session={session} onRestart={() => { setPhase('config'); setSession(null); setQuestion(null); }} />
      )}
    </div>
  );
}

/* ================= 配置入口 ================= */
function InterviewConfig(props: {
  mode: InterviewMode; setMode: (m: InterviewMode) => void;
  plans: PlanView[]; planIds: number[]; togglePlan: (id: number) => void;
  resumes: Awaited<ReturnType<typeof resumeApi.list>>; resumeId: number | null; setResumeId: (id: number | null) => void;
  difficulty: 'JUNIOR' | 'MIDDLE' | 'SENIOR'; setDifficulty: (d: 'JUNIOR' | 'MIDDLE' | 'SENIOR') => void;
  uploading: boolean; onUpload: (f: File) => void;
  err: string; creating: boolean; canStart: boolean; onStart: () => void;
}) {
  const fileRef = useRef<HTMLInputElement>(null);
  return (
    <div className="iv-config">
      {/* 面试方式 */}
      <section className="iv-block">
        <h2 className="iv-block-title">① 选择面试方式</h2>
        <div className="iv-mode-grid">
          <button
            className={'iv-mode' + (props.mode === 'TEXT' ? ' active' : '')}
            onClick={() => props.setMode('TEXT')}
          >
            <span className="iv-mode-ico"><Type size={22} strokeWidth={1.6} /></span>
            <span className="iv-mode-name">文字面试</span>
            <span className="iv-mode-desc">全程文字问答，思路清晰，适合随时练习</span>
          </button>
          <button
            className={'iv-mode' + (props.mode === 'VOICE' ? ' active' : '')}
            onClick={() => props.setMode('VOICE')}
          >
            <span className="iv-mode-ico"><Mic size={22} strokeWidth={1.6} /></span>
            <span className="iv-mode-name">语音面试</span>
            <span className="iv-mode-desc">AI 语音提问、你说 AI 听，还原真实面试</span>
          </button>
        </div>
      </section>

      {/* 面试依据 */}
      <section className="iv-block">
        <h2 className="iv-block-title">② 面试依据（简历与学习方向二选一，可都选）</h2>
        <div className="iv-source-grid">
          <Card className="iv-source">
            <div className="iv-source-head">
              <FileText size={16} strokeWidth={1.7} className="iv-source-ico" />
              <span className="iv-source-label">简历</span>
              <Badge kind={props.resumeId != null ? 'good' : 'soft'}>
                {props.resumeId != null ? '已选择' : '可选'}
              </Badge>
            </div>
            {props.resumes.length > 0 ? (
              <select
                className="iv-select"
                value={props.resumeId ?? ''}
                onChange={(e) => props.setResumeId(e.target.value ? Number(e.target.value) : null)}
              >
                <option value="">不使用简历</option>
                {props.resumes.map(r => (
                  <option key={r.id} value={r.id}>{r.originalName}</option>
                ))}
              </select>
            ) : (
              <p className="iv-source-empty">还没有简历</p>
            )}
            <input
              ref={fileRef} type="file" accept=".pdf,.txt,.md,.doc,.docx" hidden
              onChange={(e) => { const f = e.target.files?.[0]; if (f) props.onUpload(f); e.target.value = ''; }}
            />
            <Button variant="ghost" disabled={props.uploading} onClick={() => fileRef.current?.click()}>
              {props.uploading ? <><Loader2 size={14} className="spin" /> 上传中…</> : '上传新简历'}
            </Button>
          </Card>

          <Card className="iv-source">
            <div className="iv-source-head">
              <BookOpen size={16} strokeWidth={1.7} className="iv-source-ico" />
              <span className="iv-source-label">学习方向（可多选）</span>
              <Badge kind={props.planIds.length > 0 ? 'good' : 'soft'}>
                {props.planIds.length > 0 ? `已选 ${props.planIds.length} 个` : '可选'}
              </Badge>
            </div>
            {props.plans.length === 0 ? (
              <p className="iv-source-empty">还没有学习方向，去「学习计划」创建一个</p>
            ) : (
              <div className="iv-plan-chips">
                {props.plans.map(p => (
                  <button
                    key={p.id}
                    className={'iv-plan-chip' + (props.planIds.includes(p.id) ? ' active' : '')}
                    onClick={() => props.togglePlan(p.id)}
                  >
                    {p.title}
                  </button>
                ))}
              </div>
            )}
            {props.resumeId != null && props.planIds.length > 0 && (
              <p className="iv-ratio-hint">已同时选择：出题占比 <strong>简历 70%</strong> + <strong>学习方向 30%</strong></p>
            )}
          </Card>
        </div>
      </section>

      {/* 难度 */}
      <section className="iv-block">
        <h2 className="iv-block-title">③ 面试难度（决定时长与追问深度）</h2>
        <div className="iv-diff-row">
          {(['JUNIOR', 'MIDDLE', 'SENIOR'] as const).map(d => (
            <button
              key={d}
              className={'iv-diff' + (props.difficulty === d ? ' active' : '')}
              onClick={() => props.setDifficulty(d)}
            >
              {DIFFICULTY_LABEL[d]}
              <small className="iv-diff-time">{DIFFICULTY_TIME[d]}</small>
            </button>
          ))}
        </div>
        <p className="iv-ratio-hint">
          时长统一约 60 分钟；难度决定追问深度：初级浅显·数量少，中级有深度，高级深入考察真实掌握度。超时自动结束进入待评估。
        </p>
      </section>

      {props.err && <div className="banner warn">{props.err}</div>}

      <div className="iv-start-row">
        <Button className="iv-start" disabled={!props.canStart || props.creating} onClick={props.onStart}>
          {props.creating ? <><Loader2 size={16} className="spin" /> 出题中…</> : '开始面试'}
          {!props.creating && <ChevronRight size={16} strokeWidth={2} />}
        </Button>
        {!props.canStart && (
          <p className="iv-start-hint">需先上传简历或选择学习方向</p>
        )}
      </div>
    </div>
  );
}

/* ================= 面试进行 ================= */
function InterviewRun(props: {
  session: InterviewSession; question: CurrentQuestion | null; mode: InterviewMode;
  answer: string; setAnswer: (s: string) => void; busy: boolean;
  onSubmit: () => void; onComplete: () => void; onQuit: () => void;
  finished: boolean; left: number;
  listening: boolean; recogText: string;
  voiceOn: boolean; onToggleVoice: () => void; onToggleListening: () => void;
}) {
  const { session, question, mode, finished, left } = props;
  const urgent = left <= 60 && !finished;
  const totalBase = session.totalQuestions;
  const answeredBase = question ? Math.min(question.baseIndex + 1, totalBase) : totalBase;

  return (
    <div className="iv-run">
      {/* 顶部状态栏 */}
      <div className="iv-topbar">
        <div className="iv-top-meta">
          <span className="iv-q-count">
            {finished ? '面试结束' : `第 ${answeredBase} / ${totalBase} 题`}
          </span>
          <Badge kind="soft">{DIFFICULTY_LABEL[session.difficulty]}</Badge>
          <Badge kind="soft">{mode === 'VOICE' ? '语音面试' : '文字面试'}</Badge>
          {/* 倒计时 */}
          {!finished && (
            <span className={'iv-timer' + (urgent ? ' urgent' : '')}>
              <Timer size={14} strokeWidth={1.8} /> {fmtTime(left)}
            </span>
          )}
          {mode === 'VOICE' && !finished && (
            <button className={'iv-voice-toggle' + (props.voiceOn ? ' on' : '')} onClick={props.onToggleVoice}>
              <Volume2 size={14} strokeWidth={1.8} /> {props.voiceOn ? 'AI 发声中' : 'AI 发声提问'}
            </button>
          )}
          {!finished && (
            <button className="iv-quit" onClick={props.onQuit}>
              <X size={14} strokeWidth={2} /> 退出
            </button>
          )}
        </div>
      </div>

      {/* 已答对话线 */}
      {question && question.history.length > 0 && (
        <div className="iv-thread">
          {question.history.map((h, i) => (
            <div key={i} className="iv-turn">
              <div className={'iv-turn-q' + (h.followUp ? ' followup' : '')}>
                <span className="iv-turn-tag">{h.followUp ? '追问' : '主问'}</span>
                <Markdown>{h.question}</Markdown>
              </div>
              {h.answer && (
                <div className="iv-turn-a"><Markdown>{h.answer}</Markdown></div>
              )}
            </div>
          ))}
        </div>
      )}

      {finished ? (
        /* 待评估 */
        <Card className="iv-question-card">
          <span className="eyebrow">面试官</span>
          <div className="iv-question-text">
            {left <= 0 ? '面试时间已到，本场面试结束。' : '所有问题已作答完毕，本场面试结束。'}
          </div>
          <div className="iv-answer-foot" style={{ marginTop: 'var(--s-4)' }}>
            <span className="iv-answer-hint">评估后将生成总分与逐题反馈</span>
            <Button onClick={props.onComplete} disabled={props.busy}>
              {props.busy ? <><Loader2 size={15} className="spin" /> 评估中…</> : '完成评估'}
            </Button>
          </div>
        </Card>
      ) : question && question.question ? (
        <>
          {/* 当前题目 */}
          <Card className="iv-question-card">
            <span className="eyebrow">面试官 · {question.followUpIndex > 0 ? `追问 ${question.followUpIndex}/${question.totalFollowUps}` : '主问题'}</span>
            <div className="iv-question-text">
              <Markdown>{question.question}</Markdown>
            </div>
          </Card>

          {/* 作答区 */}
          <Card className="iv-answer-card">
            <span className="eyebrow">你的回答</span>
            {mode === 'VOICE' && (
              <div className="iv-voice-area">
                <button
                  className={'iv-mic' + (props.listening ? ' on' : '')}
                  onClick={props.onToggleListening}
                  disabled={props.busy}
                >
                  {props.listening ? <Square size={20} strokeWidth={2} /> : <Mic size={22} strokeWidth={1.8} />}
                </button>
                <span className="iv-mic-hint">
                  {props.listening ? '正在聆听…再点一次结束' : '点击开始说话（语音自动转文字）'}
                </span>
              </div>
            )}
            <textarea
              className="iv-answer"
              placeholder={mode === 'VOICE' ? '语音识别结果会显示在这里，也可手动修改…' : '像面试一样把思路讲清楚：结论 → 理由 → 例子…'}
              value={props.answer || props.recogText}
              onChange={(e) => props.setAnswer(e.target.value)}
              rows={7}
              disabled={props.busy}
            />
            <div className="iv-answer-foot">
              <span className="iv-answer-hint">
                {urgent ? '时间快到了，提交后将结束面试' : '提交后 AI 会根据你的回答继续追问'}
              </span>
              <Button onClick={props.onSubmit} disabled={props.busy || (!props.answer.trim() && !props.recogText.trim())}>
                {props.busy ? <><Loader2 size={15} className="spin" /> 处理中…</> : (
                  <><Send size={15} strokeWidth={1.8} /> 提交回答</>
                )}
              </Button>
            </div>
          </Card>
        </>
      ) : null}
    </div>
  );
}

/* ================= 评估结果 ================= */
function InterviewResult({ session, onRestart }: { session: InterviewSession; onRestart: () => void }) {
  const ev = session.evaluation;
  const score = session.totalScore ?? ev?.totalScore ?? 0;
  const tone = score >= 80 ? 'good' : score >= 60 ? 'warn' : 'bad';

  return (
    <div className="iv-result">
      <Card className={'iv-score-card ' + tone}>
        <div className="iv-score-left">
          <span className="eyebrow">面试评估</span>
          <div className="iv-score-big">
            <span className="iv-score-num">{score}</span>
            <span className="iv-score-total">/ 100</span>
          </div>
          <div className="iv-score-tags">
            <Badge kind="soft">{session.skillName}</Badge>
            <Badge kind="soft">{DIFFICULTY_LABEL[session.difficulty]}</Badge>
            <Badge kind="soft">{session.mode === 'VOICE' ? '语音面试' : '文字面试'}</Badge>
            <Badge kind="soft">共 {session.answers.length} 轮问答</Badge>
          </div>
        </div>
        <div className="iv-score-right">
          {score >= 80 ? <CheckCircle2 size={44} strokeWidth={1.4} /> : <XCircle size={44} strokeWidth={1.4} />}
          <span className="iv-score-word">
            {score >= 80 ? '表现优秀' : score >= 60 ? '基本达标' : '需要加强'}
          </span>
        </div>
      </Card>

      {ev && (
        <div className="iv-eval-grid">
          {ev.strength.length > 0 && (
            <Card className="iv-eval-block">
              <span className="iv-eval-title good">优势</span>
              <ul className="iv-eval-list">{ev.strength.map((s, i) => <li key={i}>{s}</li>)}</ul>
            </Card>
          )}
          {ev.improvements.length > 0 && (
            <Card className="iv-eval-block">
              <span className="iv-eval-title bad">改进建议</span>
              <ul className="iv-eval-list">{ev.improvements.map((s, i) => <li key={i}>{s}</li>)}</ul>
            </Card>
          )}
        </div>
      )}

      {session.answers.length > 0 && (
        <section className="iv-block">
          <h2 className="iv-block-title">问答回顾</h2>
          <div className="iv-qa-list">
            {session.answers.map((a) => (
              <Card key={a.id} className="iv-qa">
                <div className="iv-qa-head">
                  <span className="iv-qa-no">{a.isFollowUp ? '追问' : 'Q'}</span>
                  {a.score != null && (
                    <span className="iv-qa-score" style={{ color: a.score >= 60 ? 'var(--good)' : 'var(--bad)' }}>
                      {a.score}<small>/100</small>
                    </span>
                  )}
                </div>
                <div className="iv-qa-q"><Markdown>{a.questionText}</Markdown></div>
                {a.answerText && <div className="iv-qa-a"><span className="iv-qa-a-label">你的回答</span><Markdown>{a.answerText}</Markdown></div>}
                {a.feedback && <div className="iv-qa-f"><span className="iv-qa-a-label">反馈</span><Markdown>{a.feedback}</Markdown></div>}
              </Card>
            ))}
          </div>
        </section>
      )}

      <div className="iv-result-actions">
        <Button variant="ghost" onClick={onRestart}>再来一场</Button>
        <Button variant="primary" onClick={() => window.location.hash = '#/rehearsal/history'}>查看面试记录</Button>
      </div>
    </div>
  );
}
