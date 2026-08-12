import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { NotebookPen, Repeat2 } from 'lucide-react';
import { drill, studyPlan } from '../api/drill';
import { ApiError } from '../api/client';
import { Loading, Badge } from '../components/ui';
import { useActivePlan } from '../lib/useActivePlan';
import { GRADE_LABEL, gradeClass } from '../lib/labels';
import type { RunSummaryView, PlanView } from '../api/types';
import './History.css';

function msg(e: unknown): string {
  return e instanceof ApiError ? e.message : '加载失败';
}

export function HistoryPage() {
  const navigate = useNavigate();
  const [items, setItems] = useState<RunSummaryView[]>([]);
  const [plans, setPlans] = useState<PlanView[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState('');

  useEffect(() => {
    let alive = true;
    setLoading(true);
    Promise.all([drill.history(), studyPlan.list()])
      .then(([rows, p]) => {
        if (alive) {
          setItems(rows);
          setPlans(p);
        }
      })
      .catch((e) => {
        if (alive) setErr(msg(e));
      })
      .finally(() => {
        if (alive) setLoading(false);
      });
    return () => {
      alive = false;
    };
  }, []);

  // 当前学习方向（切换需确认）：问答记录按方向过滤
  const { activeId } = useActivePlan(plans);
  const itemsActive = items.filter((it) => it.planId === activeId);

  return (
    <div className="page">
      <header className="page-head">
        <span className="eyebrow">练习 · 档案</span>
        <h1>问答记录</h1>
        <p>
          按题归成对话线：原答、重答、追问都串在同一道题下。点卡片直接回到那道题的对话框，接着聊。
        </p>
      </header>

      {err && <div className="banner info">{err}</div>}

      {loading ? (
        <Loading label="读取问答记录…" />
      ) : itemsActive.length === 0 ? (
        <div className="empty">
          <h3>这个方向还没有作答记录</h3>
          <p>去「练习」选这个方向答几道题，判分后就会出现在这里。</p>
        </div>
      ) : (
        <div className="hist-list">
          {itemsActive.map((it) => (
            <button
              className="hist-row"
              key={it.questionId}
              onClick={() =>
                navigate('/drill', { state: { viewQuestionId: it.questionId }, replace: true })
              }
            >
              <div className="hist-main">
                <span className="hist-stem">{it.stem}</span>
                <span className="hist-meta">
                  {it.status !== 'GRADED' ? (
                    <Badge kind="soft">进行中</Badge>
                  ) : (
                    <>
                      <Badge kind={gradeClass(it.grade)}>{GRADE_LABEL[it.grade ?? ''] ?? '—'}</Badge>
                      <span className="hist-score">{it.rawScore.toFixed(1)} 分</span>
                    </>
                  )}
                  {it.runCount > 1 && (
                    <span className="hist-count" title="该题练过多轮">
                      <Repeat2 size={13} strokeWidth={1.7} /> {it.runCount} 轮
                    </span>
                  )}
                  {it.hasNote && (
                    <span className="hist-note" title="已写内化笔记">
                      <NotebookPen size={14} strokeWidth={1.6} />
                    </span>
                  )}
                </span>
              </div>
              <span className="hist-date">{fmtDate(it.answeredAt)}</span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

function fmtDate(iso: string): string {
  try {
    return new Date(iso).toLocaleString('zh-CN', {
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    });
  } catch {
    return iso;
  }
}
