import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Mic, Type, ArrowLeft } from 'lucide-react';
import { interviewApi, type InterviewSession } from '../api/interview';
import { Card, Loading } from '../components/ui';
import { Markdown } from '../components/Markdown';
import { ApiError } from '../api/client';
import './Interview.css';

function msg(e: unknown): string {
  return e instanceof ApiError ? e.message : '加载失败';
}

const DIFF_LABEL: Record<string, string> = { JUNIOR: '初级', MIDDLE: '中级', SENIOR: '高级' };

export function InterviewDetail() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [session, setSession] = useState<InterviewSession | null>(null);
  const [err, setErr] = useState('');

  useEffect(() => {
    if (!id) return;
    interviewApi.detail(id).then(setSession).catch((e) => setErr(msg(e)));
  }, [id]);

  if (err) return <div className="page"><div className="banner info">{err}</div></div>;

  if (!session) return <div className="page"><Loading label="加载面试详情…" /></div>;

  const ev = session.evaluation;
  const score = session.totalScore ?? 0;
  const scoreTone = score >= 80 ? 'good' : score >= 60 ? 'warn' : 'bad';

  return (
    <div className="page">
      <header className="page-head">
        <button className="ih-back" onClick={() => navigate('/rehearsal/history')}>
          <ArrowLeft size={15} strokeWidth={2} /> 返回历史
        </button>
        <span className="eyebrow">模拟面试 · DETAIL</span>
        <h1>{session.skillName}</h1>
        <p>
          {new Date(session.createdAt).toLocaleString('zh-CN')} · {DIFF_LABEL[session.difficulty]} ·
          {session.mode === 'VOICE' ? <><Mic size={12} strokeWidth={1.8} /> 语音面试</> : <><Type size={12} strokeWidth={1.8} /> 文字面试</>} ·
          共 {session.totalQuestions} 题
        </p>
      </header>

      {session.status === 'COMPLETED' && (
        <Card className={'iv-score-card ' + scoreTone} style={{ marginBottom: 'var(--s-5)' }}>
          <div className="iv-score-left">
            <span className="eyebrow">面试评估</span>
            <div className="iv-score-big">
              <span className="iv-score-num">{score}</span>
              <span className="iv-score-total">/ 100</span>
            </div>
          </div>
          <div className="iv-score-right">
            <span className="iv-score-word">{score >= 80 ? '表现优秀' : score >= 60 ? '基本达标' : '需要加强'}</span>
          </div>
        </Card>
      )}

      {ev && (
        <div className="iv-eval-grid" style={{ marginBottom: 'var(--s-5)' }}>
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
            {session.answers.map((a, i) => (
              <Card key={a.id} className="iv-qa">
                <div className="iv-qa-head">
                  <span className="iv-qa-no">Q{i + 1}{a.isFollowUp ? '（追问）' : ''}</span>
                  {a.score != null && (
                    <span className="iv-qa-score" style={{ color: a.score >= 60 ? 'var(--good)' : 'var(--bad)' }}>
                      {a.score}<small>/100</small>
                    </span>
                  )}
                </div>
                <div className="iv-qa-q"><Markdown>{a.questionText}</Markdown></div>
                {a.answerText && (
                  <div className="iv-qa-a"><span className="iv-qa-a-label">你的回答</span><Markdown>{a.answerText}</Markdown></div>
                )}
                {a.feedback && (
                  <div className="iv-qa-f"><span className="iv-qa-a-label">反馈</span><Markdown>{a.feedback}</Markdown></div>
                )}
              </Card>
            ))}
          </div>
        </section>
      )}
    </div>
  );
}
