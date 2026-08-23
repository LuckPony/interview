import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Mic, Type, ArrowRight, Clock, CalendarDays } from 'lucide-react';
import { interviewApi, type InterviewListItem } from '../api/interview';
import { Card, Badge, Loading } from '../components/ui';
import { ApiError } from '../api/client';
import './Interview.css';

function msg(e: unknown): string {
  return e instanceof ApiError ? e.message : '加载失败';
}

const DIFF_LABEL: Record<string, string> = { JUNIOR: '初级', MIDDLE: '中级', SENIOR: '高级' };

/** 评分文字颜色：≥80 绿 / ≥60 黄 / <60 红 */
function scoreTone(s: number | null): string {
  if (s == null) return 'pending';
  if (s >= 80) return 'good';
  if (s >= 60) return 'warn';
  return 'bad';
}

function fmtDate(iso: string): string {
  const d = new Date(iso);
  const now = new Date();
  const sameDay = d.toDateString() === now.toDateString();
  const p = (n: number) => String(n).padStart(2, '0');
  return `${sameDay ? '今天' : `${d.getMonth() + 1}月${d.getDate()}日`} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

export function InterviewHistory() {
  const navigate = useNavigate();
  const [list, setList] = useState<InterviewListItem[] | null>(null);
  const [err, setErr] = useState('');

  useEffect(() => {
    interviewApi.list().then(setList).catch((e) => setErr(msg(e)));
  }, []);

  return (
    <div className="page">
      <header className="page-head">
        <span className="eyebrow">模拟面试 · HISTORY</span>
        <h1>面试历史</h1>
        <p>每一次模拟面试的评分与评估，点击查看完整问答回顾。</p>
      </header>

      {err && <div className="banner info">{err}</div>}

      {list === null ? (
        <Loading label="读取面试历史…" />
      ) : list.length === 0 ? (
        <div className="empty">
          <h3>还没有面试记录</h3>
          <p>去「模拟面试」开始你的第一场吧。</p>
        </div>
      ) : (
        <div className="ih-list">
          {list.map((it) => {
            const tone = scoreTone(it.totalScore);
            return (
              <Card
                key={it.id}
                className="ih-item"
                onClick={() => it.status === 'IN_PROGRESS'
                  ? navigate(`/rehearsal?resume=${it.id}`)
                  : navigate(`/rehearsal/history/${it.id}`)}
              >
                <div className="ih-left">
                  <div className="ih-title-row">
                    <span className="ih-title">{it.skillName}</span>
                    <Badge kind="soft">{DIFF_LABEL[it.difficulty] ?? it.difficulty}</Badge>
                    <span className="ih-mode">
                      {it.mode === 'VOICE' ? <Mic size={12} strokeWidth={1.8} /> : <Type size={12} strokeWidth={1.8} />}
                      {it.mode === 'VOICE' ? '语音' : '文字'}
                    </span>
                  </div>
                  <div className="ih-meta">
                    <span className="ih-meta-item">
                      <CalendarDays size={12} strokeWidth={1.8} /> {fmtDate(it.createdAt)}
                    </span>
                    <span className="ih-meta-item">
                      <Clock size={12} strokeWidth={1.8} /> {it.answeredCount}/{it.totalQuestions} 题
                    </span>
                    <Badge kind={it.status === 'COMPLETED' ? 'good' : it.status === 'IN_PROGRESS' ? 'warn' : 'soft'}>
                      {it.status === 'COMPLETED' ? '已完成' : it.status === 'IN_PROGRESS' ? '进行中' : '已退出'}
                    </Badge>
                  </div>
                </div>

                <div className="ih-right">
                  {it.totalScore != null ? (
                    <div className={'ih-score ' + tone}>
                      <span className="ih-score-num">{it.totalScore}</span>
                      <span className="ih-score-total">/100</span>
                    </div>
                  ) : (
                    <span className="ih-score-pending">未评分</span>
                  )}
                  <ArrowRight size={16} strokeWidth={1.8} className="ih-arrow" />
                </div>
              </Card>
            );
          })}
        </div>
      )}
    </div>
  );
}
