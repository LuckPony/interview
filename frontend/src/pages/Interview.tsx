import { useEffect, useRef, useState } from 'react';

import {
  Mic, Type, FileText, BookOpen, ChevronRight, X,
  CheckCircle2, XCircle, Send, Volume2, Square, Loader2,
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

type Phase = 'config' | 'interview' | 'result';

/** 语音识别（Web Speech API，Chrome 免费内置，无需付费） */
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

/** AI 发声（浏览器内置 speechSynthesis，免费；音质取决于系统语音包） */
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

/** 进行中面试会话持久化：切走再回来可恢复 */
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
  const [questionCount, setQuestionCount] = useState(5);
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
  const recRef = useRef<any>(null);
  const [err, setErr] = useState('');

  useEffect(() => {
    studyPlan.list().then(setPlans).catch(() => {});
    resumeApi.list().then(setResumes).catch(() => {});
  }, []);

  // 恢复进行中的面试（切走再回来 / 从历史点击继续）
  useEffect(() => {
    const resumeId = new URLSearchParams(window.location.hash.split('?')[1] ?? '').get('resume')
      ?? sessionStorage.getItem(SESSION_KEY);
    if (!resumeId) return;
    let alive = true;
    (async () => {
      try {
        const s = await interviewApi.detail(resumeId);
        if (!alive) return;
        if (s.status !== 'IN_PROGRESS') {
          sessionStorage.removeItem(SESSION_KEY);
          return;
        }
        setSession(s);
        setPhase('interview');
        try {
          const q = await interviewApi.currentQuestion(resumeId);
          if (!alive) return;
          setQuestion(q);
          setAnswer('');
          setRecogText('');
          if (s.mode === 'VOICE') speak('第' + (q.currentIndex + 1) + '题：' + q.question);
        } catch (e) {
          // 题目已答完但未评估：提示完成评估
          if (alive) { setSession(s); setQuestion(null); setErr('题目已全部答完，请点击「完成评估」'); }
        }
      } catch { /* 会话不存在/过期，忽略 */ }
    })();
    return () => { alive = false; };
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

  const startInterview = async () => {
    if (!resumeId && planIds.length === 0) {
      setConfigErr('请上传简历或选择至少一个学习方向，才能开始面试（二选一，可都选）');
      return;
    }
    setConfigErr('');
    setCreating(true);
    try {
      const s = await interviewApi.createSession({
        resumeId,
        planIds,
        difficulty,
        questionCount,
        mode,
      });
      sessionStorage.setItem(SESSION_KEY, s.id);
      setSession(s);
      setPhase('interview');
      await loadQuestion(s.id);
    } catch (e) {
      setConfigErr(msg(e));
      setCreating(false);
    }
  };

  const loadQuestion = async (sessionId: string) => {
    const q = await interviewApi.currentQuestion(sessionId);
    setQuestion(q);
    setAnswer('');
    setRecogText('');
    // 语音模式：AI 发声提问
    if (mode === 'VOICE') speak(`第${q.currentIndex + 1}题：${q.question}`);
    return q;
  };

  const completeNow = async (sid: string) => {
    setBusy(true);
    setErr('');
    try {
      const done = await interviewApi.completeAndEvaluate(sid);
      sessionStorage.removeItem(SESSION_KEY);
      setSession(done);
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
      await interviewApi.submitAnswer(session.id, question.currentIndex, text);
      if (question.currentIndex + 1 >= question.totalQuestions) {
        // 全部答完 → 评估
        await completeNow(session.id);
      } else {
        await loadQuestion(session.id);
      }
    } catch (e) {
      setErr(msg(e));
    } finally {
      setBusy(false);
    }
  };

  const quit = () => {
    if (!window.confirm('确定退出本场面试吗？退出后本次作答不计分。')) return;
    if (recRef.current) recRef.current.stop();
    sessionStorage.removeItem(SESSION_KEY);
    setPhase('config');
    setSession(null);
    setQuestion(null);
    setAnswer('');
    setErr('');
  };

  const toggleVoice = () => {
    setVoiceOn(v => !v);
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

  return (
    <div className="page interview-page">
      <header className="page-head">
        <span className="eyebrow">模拟面试 · INTERVIEW</span>
        <h1>模拟面试</h1>
        <p>基于简历与学习方向出题，30-40 分钟一场，结束后 AI 评估打分。</p>
      </header>

      <div className="iv-header-actions">
        <a className="ih-back" href="#/rehearsal/history">查看面试历史</a>
      </div>

      {err && <div className="banner warn">{err}</div>}

      {phase === 'config' && (
        <InterviewConfig
          mode={mode} setMode={setMode}
          plans={plans} planIds={planIds} togglePlan={togglePlan}
          resumes={resumes} resumeId={resumeId} setResumeId={setResumeId}
          difficulty={difficulty} setDifficulty={setDifficulty}
          questionCount={questionCount} setQuestionCount={setQuestionCount}
          uploading={uploading} onUpload={uploadResume}
          err={configErr} creating={creating}
          canStart={!!resumeId || planIds.length > 0}
          onStart={startInterview}
        />
      )}

      {phase === 'interview' && session && !question && (
        <Card className="iv-question-card">
          <span className="eyebrow">面试官</span>
          <div className="iv-question-text">所有题目已作答完毕，可以进行评估了。</div>
          <div className="iv-answer-foot" style={{ marginTop: 'var(--s-4)' }}>
            <span className="iv-answer-hint">评估后将生成总分与逐题反馈</span>
            <Button onClick={() => completeNow(session.id)} disabled={busy}>
              {busy ? <><Loader2 size={15} className="spin" /> 评估中…</> : '完成评估'}
            </Button>
          </div>
        </Card>
      )}

      {phase === 'interview' && session && question && (
        <InterviewRun
          session={session}
          question={question}
          mode={mode}
          answer={answer}
          setAnswer={setAnswer}
          busy={busy}
          onSubmit={submit}
          onQuit={quit}
          listening={listening}
          recogText={recogText}
          voiceOn={voiceOn}
          onToggleVoice={toggleVoice}
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
  questionCount: number; setQuestionCount: (n: number) => void;
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
          {/* 简历 */}
          <Card className="iv-source">
            <div className="iv-source-head">
              <FileText size={16} strokeWidth={1.7} className="iv-source-ico" />
              <span className="iv-source-label">简历</span>
              <Badge kind={props.resumeId != null ? 'good' : 'soft'}>
                {props.resumeId != null ? '已选择' : '可选'}
              </Badge>
            </div>
            {((props.resumes ?? []).length) > 0 ? (
              <select
                className="iv-select"
                value={props.resumeId ?? ''}
                onChange={(e) => props.setResumeId(e.target.value ? Number(e.target.value) : null)}
              >
                <option value="">不使用简历</option>
                {(props.resumes ?? []).map(r => (
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

          {/* 学习方向 */}
          <Card className="iv-source">
            <div className="iv-source-head">
              <BookOpen size={16} strokeWidth={1.7} className="iv-source-ico" />
              <span className="iv-source-label">学习方向（可多选）</span>
              <Badge kind={props.planIds.length > 0 ? 'good' : 'soft'}>
                {props.planIds.length > 0 ? `已选 ${props.planIds.length} 个` : '可选'}
              </Badge>
            </div>
            {((props.plans ?? []).length) === 0 ? (
              <p className="iv-source-empty">还没有学习方向，去「学习计划」创建一个</p>
            ) : (
              <div className="iv-plan-chips">
                {(props.plans ?? []).map(p => (
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
        <h2 className="iv-block-title">③ 面试难度</h2>
        <div className="iv-diff-row">
          {(['JUNIOR', 'MIDDLE', 'SENIOR'] as const).map(d => (
            <button
              key={d}
              className={'iv-diff' + (props.difficulty === d ? ' active' : '')}
              onClick={() => props.setDifficulty(d)}
            >
              {DIFFICULTY_LABEL[d]}
            </button>
          ))}
        </div>
      </section>

      {/* 题目数量 */}
      <section className="iv-block">
        <h2 className="iv-block-title">④ 题目数量</h2>
        <div className="iv-diff-row">
          {[3, 5, 8].map(n => (
            <button
              key={n}
              className={'iv-diff' + (props.questionCount === n ? ' active' : '')}
              onClick={() => props.setQuestionCount(n)}
            >
              {n} 题
            </button>
          ))}
          <span className="iv-count-hint">约 {props.questionCount * 6}-{props.questionCount * 8} 分钟</span>
        </div>
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
  session: InterviewSession; question: CurrentQuestion; mode: InterviewMode;
  answer: string; setAnswer: (s: string) => void; busy: boolean;
  onSubmit: () => void; onQuit: () => void;
  listening: boolean; recogText: string;
  voiceOn: boolean; onToggleVoice: () => void; onToggleListening: () => void;
}) {
  const { session, question, mode } = props;
  const progress = Math.round((question.currentIndex / question.totalQuestions) * 100);

  return (
    <div className="iv-run">
      {/* 顶部状态栏 */}
      <div className="iv-topbar">
        <div className="iv-progress">
          <div className="iv-progress-bar" style={{ width: `${progress}%` }} />
        </div>
        <div className="iv-top-meta">
          <span className="iv-q-count">第 {question.currentIndex + 1} / {question.totalQuestions} 题</span>
          <Badge kind="soft">{mode === 'VOICE' ? '语音面试' : '文字面试'}</Badge>
          <Badge kind="soft">{DIFFICULTY_LABEL[session.difficulty]}</Badge>
          {mode === 'VOICE' && (
            <button className={'iv-voice-toggle' + (props.voiceOn ? ' on' : '')} onClick={props.onToggleVoice}>
              <Volume2 size={14} strokeWidth={1.8} /> {props.voiceOn ? 'AI 发声中' : 'AI 发声提问'}
            </button>
          )}
          <button className="iv-quit" onClick={props.onQuit}>
            <X size={14} strokeWidth={2} /> 退出
          </button>
        </div>
      </div>

      {/* 题目卡片 */}
      <Card className="iv-question-card">
        <span className="eyebrow">面试官</span>
        <div className="iv-question-text">
          <Markdown>{question.question}</Markdown>
        </div>
        {question.followUps.length > 0 && (
          <div className="iv-followups">
            <span className="iv-followups-label">追问提示</span>
            {question.followUps.map((f, i) => (
              <p key={i} className="iv-followup">· {f}</p>
            ))}
          </div>
        )}
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
          rows={8}
          disabled={props.busy}
        />

        <div className="iv-answer-foot">
          <span className="iv-answer-hint">
            {question.currentIndex + 1 >= question.totalQuestions ? '最后一题，提交后进入评估' : '提交后进入下一题'}
          </span>
          <Button onClick={props.onSubmit} disabled={props.busy || (!props.answer.trim() && !props.recogText.trim())}>
            {props.busy ? <><Loader2 size={15} className="spin" /> 处理中…</> : (
              <><Send size={15} strokeWidth={1.8} /> {question.currentIndex + 1 >= question.totalQuestions ? '提交并评估' : '提交回答'}</>
            )}
          </Button>
        </div>
      </Card>
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
            <Badge kind="soft">共 {session.totalQuestions} 题</Badge>
          </div>
        </div>
        <div className="iv-score-right">
          {score >= 80 ? <CheckCircle2 size={44} strokeWidth={1.4} /> : <XCircle size={44} strokeWidth={1.4} />}
          <span className="iv-score-word">
            {score >= 80 ? '表现优秀' : score >= 60 ? '基本达标' : '需要加强'}
          </span>
        </div>
      </Card>

      {/* 优势 / 建议 */}
      {ev && (
        <div className="iv-eval-grid">
          {ev.strength.length > 0 && (
            <Card className="iv-eval-block">
              <span className="iv-eval-title good">优势</span>
              <ul className="iv-eval-list">
                {ev.strength.map((s, i) => <li key={i}>{s}</li>)}
              </ul>
            </Card>
          )}
          {ev.improvements.length > 0 && (
            <Card className="iv-eval-block">
              <span className="iv-eval-title bad">改进建议</span>
              <ul className="iv-eval-list">
                {ev.improvements.map((s, i) => <li key={i}>{s}</li>)}
              </ul>
            </Card>
          )}
        </div>
      )}

      {/* 逐题回顾 */}
      {session.answers.length > 0 && (
        <section className="iv-block">
          <h2 className="iv-block-title">逐题回顾</h2>
          <div className="iv-qa-list">
            {session.answers.map((a, i) => (
              <Card key={a.id} className="iv-qa">
                <div className="iv-qa-head">
                  <span className="iv-qa-no">Q{i + 1}</span>
                  <span className="iv-qa-score" style={{ color: (a.score ?? 0) >= 60 ? 'var(--good)' : 'var(--bad)' }}>
                    {a.score ?? '—'}<small>/100</small>
                  </span>
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
        <Button variant="primary" onClick={() => window.location.hash = '#/rehearsal/history'}>查看面试历史</Button>
      </div>
    </div>
  );
}
