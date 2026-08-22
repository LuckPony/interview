import {useEffect, useState} from 'react';
import { MicOff, CheckCircle2, XCircle, RotateCcw, MessagesSquare } from 'lucide-react';
import {drill, rehearsalAnswerStream, studyPlan} from '../api/drill';
import { Button, Badge, Loading } from '../components/ui';
import { ApiError } from '../api/client';
import { GRADE_LABEL, gradeClass } from '../lib/labels';
import type {PlanView, RehearsalView} from '../api/types';
import { Markdown } from '../components/Markdown';
import './Rehearsal.css';

function msg(e: unknown): string {
  return e instanceof ApiError ? e.message : '加载失败';
}

export function Rehearsal() {
  const [view, setView] = useState<RehearsalView | null>(null);
  const [answer, setAnswer] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  const [started, setStarted] = useState(false);
  // 流式讲解：逐 token 累积，done 后定稿。key 用作答轮 round，避免被下一轮 result 覆盖。
  const [tutor, setTutor] = useState<{ round: number; text: string; done: boolean } | null>(null);

  const [plans, setPlans] = useState<PlanView[]>([]);

  useEffect(() => {
    studyPlan.list().then(setPlans).catch(() => {});
  }, []);

  const start = async () => {
    setBusy(true);
    setErr('');
    setStarted(true);
    try {
      setView(await drill.rehearsalStart());
      setAnswer('');
    } catch (e) {
      setErr(msg(e));
      setStarted(false);
    } finally {
      setBusy(false);
    }
  };

  const startFromPlan = async (planId: number) => {
    setBusy(true);
    setErr('');
    setStarted(true);
    try {
      setView(await drill.rehearsalStartFromPlan(planId));
      setAnswer('');
    } catch (e) {
      setErr(msg(e));
      setStarted(false);
    } finally {
      setBusy(false);
    }
  };

  const answerRound = () => {
    if (!view || !answer.trim()) {
      setErr('先作答，再进入下一轮。');
      return;
    }
    const runId = view.runId;
    const round = view.round; // 本轮即作答轮
    setBusy(true);
    setErr('');
    setAnswer('');
    rehearsalAnswerStream(
      runId,
      answer,
      (v) => {
        setView(v);
      },
      (token) => {
        setTutor((prev) =>
          prev && prev.round === round ? { ...prev, text: prev.text + token } : { round, text: token, done: false },
        );
      },
      (fullText) => {
        // 流结束：用后端带回的完整 text 覆盖本地累积（兜底末尾截断）
        setTutor((prev) =>
          prev && prev.round === round ? { ...prev, text: fullText ?? prev.text, done: true } : prev,
        );
        setBusy(false);
      },
      (_status?: number, message?: string) => {
        setErr(message || '提交失败');
        setBusy(false);
      },
    );
  };

  const restart = () => {
    setView(null);
    setStarted(false);
    setErr('');
    setAnswer('');
    setTutor(null);
  };

  const phase: 'start' | 'asking' | 'settled' = !started
    ? 'start'
    : view && view.finished
      ? 'settled'
      : 'asking';

  return (
    <div className="page">
      <header className="page-head">
        <span className="eyebrow">模拟面试 · REHEARSAL</span>
        <h1>模拟面试</h1>
        <p>闭卷、计时、多轮追问。把上一轮答出的东西讲透，达标才算这一层真的会。</p>
      </header>

      {err && <div className="banner info">{err}</div>}

      {phase === 'start' && (
        <div className="rehearsal-start card">
          <p>选择进入模拟面试的方式：</p>
          <Button onClick={start} disabled={busy}>系统帮我选</Button>

          {plans.length > 0 && (
              <div className="rehearsal-plans">
                <p className="rehearsal-plans-label">从学习计划开始：</p>
                {plans.map((p) => (
                    <Button key={p.id} variant="ghost" disabled={busy} onClick={() => startFromPlan(p.id)}>
                      {p.title}
                    </Button>
                ))}
              </div>
          )}
          <span className="rehearsal-ico">
            <MessagesSquare size={26} strokeWidth={1.5} />
          </span>
          <h2>一场多轮的口头考核</h2>
          <p>
            系统挑一个你已达 L2 的概念开考，逐轮追问直到讲不透为止。
            全部轮次都讲清楚，才发这一层的「面试达标」。
          </p>
          <div className="rehearsal-note">
            <MicOff size={15} strokeWidth={1.6} /> 无语音版：纯文本作答，专注表达与逻辑。
          </div>
          <Button onClick={start} disabled={busy}>
            {busy ? '开场中…' : '开始模拟面试'}
          </Button>
        </div>
      )}

      {phase === 'asking' && view && (
        <div className="rehearsal-ask">
          <div className="round-bar">
            {Array.from({ length: view.maxRound + 1 }).map((_, i) => (
              <span
                key={i}
                className={'round-dot' + (i <= view.round ? ' on' : '')}
              />
            ))}
            <span className="round-label">
              第 {view.round + 1} / {view.maxRound + 1} 轮
            </span>
            <span className="closed-tag">
              <MicOff size={13} strokeWidth={1.6} /> 闭卷·计时
            </span>
          </div>

          <div className="rehearsal-card card">
            <p className="rehearsal-stem">{view.stem}</p>
            <textarea
              className="rehearsal-answer"
              placeholder="像面试一样，把思路讲出来，而不是默写定义。"
              value={answer}
              onChange={(e) => setAnswer(e.target.value)}
              rows={7}
            />
            <div className="rehearsal-foot">
              <Button onClick={answerRound} disabled={busy}>
                {busy ? '判分中…' : view.round >= view.maxRound ? '结算本场' : '提交本轮'}
              </Button>
            </div>
          </div>
        </div>
      )}

      {phase === 'settled' && view && (
        <div className="rehearsal-settled">
          <div className={`settle-card card ${view.allPassed ? 'pass' : 'fail'}`}>
            <div className="settle-icon">
              {view.allPassed ? (
                <CheckCircle2 size={40} strokeWidth={1.5} />
              ) : (
                <XCircle size={40} strokeWidth={1.5} />
              )}
            </div>
            <h2>{view.allPassed ? '面试达标' : '尚未达标'}</h2>
            <p className="settle-sub">
              {view.allPassed
                ? '整场都讲清楚了，这一层可以算真正掌握。'
                : '有一轮没讲透，回去再练，别急着标「会了」。'}
            </p>
            <div className="settle-score">
              <span>{view.score != null ? Math.round(view.score) : '—'}</span>
              <small>分</small>
              <Badge kind={gradeClass(view.grade)}>
                {view.grade ? GRADE_LABEL[view.grade] ?? view.grade : '—'}
              </Badge>
            </div>
            {view.roundScores.length > 0 && (
              <div className="round-scores">
                {view.roundScores.map((g, i) => (
                  <Badge key={i} kind={gradeClass(g)}>
                    {GRADE_LABEL[g] ?? g}
                  </Badge>
                ))}
              </div>
            )}
          </div>
          <Button variant="ghost" onClick={restart}>
            <RotateCcw size={16} strokeWidth={1.6} /> 再来一场
          </Button>
        </div>
      )}

      {tutor && (
        <div className="rehearsal-explain card">
          <span className="eyebrow">本轮讲解</span>
          {tutor.done ? (
            <Markdown className="md">{tutor.text}</Markdown>
          ) : tutor.text.length === 0 ? (
            <p className="tutor-thinking">思考中…</p>
          ) : (
            <p className="rehearsal-explain-live">{tutor.text}<span className="tutor-caret" /></p>
          )}
        </div>
      )}

      {busy && phase !== 'start' && phase !== 'settled' && <Loading label="判分中…" />}
    </div>
  );
}
