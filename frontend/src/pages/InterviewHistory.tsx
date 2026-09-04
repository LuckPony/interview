import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Mic, Type, ArrowRight, Clock, CalendarDays, X } from 'lucide-react';
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
  const [deletingId, setDeletingId] = useState<string | null>(null);

  useEffect(() => {
    interviewApi.list().then(setList).catch((e) => setErr(msg(e)));
  }, []);

  /** 删除面试记录：二次确认后调用后端并乐观更新列表 */
  const del = async (id: string) => {
    if (!window.confirm('是否确认要删除这条面试记录？删除后不可恢复。')) return;
    setDeletingId(id);
    setErr('');
    try {
      await interviewApi.delete(id);
      setList((prev) => (prev ?? []).filter((x) => x.id !== id));
    } catch (e) {
      setErr(msg(e));
    } finally {
      setDeletingId(null);
    }
  };

  return (
    <div className="page">
      <header className="page-head">
        <span className="eyebrow">面试准备 · RECORDS</span>
        <h1>面试记录</h1>
        <p>每一次模拟面试的评分与评估，点击查看完整问答回顾。</p>
      </header>

      {err && <div className="banner info">{err}</div>}

      {list === null ? (
        <Loading label="读取面试记录…" />
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
                onClick={() => it.status === 'IN_PROGRESS' || it.status === 'PENDING_EVALUATION'
                  ? navigate(`/rehearsal?resume=${it.id}`)
                  : navigate(`/rehearsal/history/${it.id}`)}
              >
                <button
                  className="ih-delete-btn"
                  title="删除这条面试记录"
                  disabled={deletingId === it.id}
                  onClick={(e) => { e.stopPropagation(); del(it.id); }}
                >
                  <X size={14} strokeWidth={2} />
                </button>
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
                    {it.status === 'COMPLETED' ? (
                      <Badge kind="good">已完成</Badge>
                    ) : it.status === 'IN_PROGRESS' ? (
                      <Badge kind="warn">进行中</Badge>
                    ) : it.status === 'PENDING_EVALUATION' ? (
                      <Badge kind="accent">待评估</Badge>
                    ) : (
                      <Badge kind="soft">已退出</Badge>
                    )}
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
