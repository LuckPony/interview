import { useEffect, useState } from 'react';
import { Play, RefreshCw, Shuffle, CalendarClock, CheckCircle2 } from 'lucide-react';
import { drill } from '../api/drill';
import { Button, Card, Tag, Badge } from '../components/ui';
import { useActivePlan } from '../lib/useActivePlan';
import type { PlanView, DailyTaskView } from '../api/types';

/** /drill 的入口态：列我的学习方向，继续/复习/点概念开练，或新建方向。 */
export function Plans({
  plans,
  onPick,
  onContinue,
  onReview,
  onFree,
  onStartTask,
  err,
  teachFirst,
  onToggleTeachFirst,
}: {
  plans: PlanView[];
  onPick: (conceptId: number) => void;
  onContinue: (planId: number) => void;
  onReview: (planId: number) => void;
  onFree?: () => void;
  onStartTask: (taskId: number) => void;
  err: string;
  teachFirst: boolean;
  onToggleTeachFirst: (v: boolean) => void;
}) {
  // 今日任务（每日自动排期 + 预生成，点开即答）；按当前学习方向过滤
  const [today, setToday] = useState<DailyTaskView[] | null>(null);
  useEffect(() => {
    let alive = true;
    drill
      .today()
      .then((t) => {
        if (alive) setToday(t);
      })
      .catch(() => {
        if (alive) setToday([]);
      });
    return () => {
      alive = false;
    };
  }, []);

  // 当前学习方向（在首页切换，这里只跟随）：练习首页也只展示该方向
  const { activeId } = useActivePlan(plans);
  const todayTasks = today?.filter((t) => t.planId === activeId) ?? [];
  // 待办任务（PENDING/READY）可点；已完成的置灰展示为「已完成」，防止重复练习同一题
  const todayActive = todayTasks.filter((t) => t.status !== 'DONE' && t.status !== 'SKIPPED');
  const todayDone = todayTasks.filter((t) => t.status === 'DONE');
  const planList = plans.filter((p) => p.id === activeId);
  const reviewCount = todayActive.filter((t) => t.kind === 'REVIEW').length;
  const newCount = todayActive.filter((t) => t.kind === 'NEW').length;

  return (
    <div className="page">
      <header className="page-head">
        <span className="eyebrow">练习 · 快速开始</span>
        <h1>选一条路开始</h1>
        <p>点「继续学习」直接出题。想看完整大纲和概念树，去顶栏「学习计划」。</p>
        <label className="teach-toggle">
          <input
            type="checkbox"
            checked={teachFirst}
            onChange={(e) => onToggleTeachFirst(e.target.checked)}
          />
          先讲解再练习（点知识点时先拆子点、逐个教考）
        </label>
      </header>

      {err && <div className="banner info">{err}</div>}

      {planList.length > 0 && (
        <Card className="today-card">
          <div className="today-head">
            <CalendarClock size={16} strokeWidth={1.6} />
            <span className="today-title">今日任务</span>
            <span className="today-count">
              {reviewCount > 0 && <span className="today-kind today-review">复习 {reviewCount}</span>}
              {newCount > 0 && <span className="today-kind today-new">新学 {newCount}</span>}
            </span>
          </div>
          {todayActive.length === 0 && todayDone.length === 0 ? (
            <p className="today-empty">今天这个方向的复习/新学都安排完了，去「继续学习」滚动复习吧。</p>
          ) : (
            <div className="today-list">
              {todayActive.map((t) => (
                <button className="today-item" key={t.id} onClick={() => onStartTask(t.id)}>
                  <Badge kind={t.kind === 'REVIEW' ? 'warn' : 'good'}>
                    {t.kind === 'REVIEW' ? '复习' : '新学'}
                  </Badge>
                  <span className="today-concept">
                    {t.conceptName} <em>L{t.layer}</em>
                  </span>
                  {t.stem ? (
                    <span className="today-stem">{t.stem.slice(0, 56)}…</span>
                  ) : (
                    <span className="today-pending">题目生成中…</span>
                  )}
                  <Play size={13} strokeWidth={1.8} className="today-arrow" />
                </button>
              ))}
              {todayDone.map((t) => (
                <div className="today-item today-done" key={t.id}>
                  <Badge kind="good">
                    {t.kind === 'REVIEW' ? '复习' : '新学'}
                  </Badge>
                  <span className="today-concept">
                    {t.conceptName} <em>L{t.layer}</em>
                  </span>
                  <span className="today-stem">已完成</span>
                  <CheckCircle2 size={14} strokeWidth={2} className="today-done-icon" />
                </div>
              ))}
            </div>
          )}
        </Card>
      )}

      <div className="plan-actions">
        {onFree && (
          <Button variant="ghost" onClick={onFree}>
            <Shuffle size={16} strokeWidth={1.6} /> 系统帮我选一题
          </Button>
        )}
      </div>

      {planList.length === 0 ? (
        <div className="empty">
          <h3>当前方向暂无学习计划</h3>
          <p>去「首页」切换学习方向，或点上面「新建学习方向」。</p>
        </div>
      ) : (
        <div className="plan-list">
          {planList.map((p) => (
            <Card className="plan-card" key={p.id}>
              <div className="plan-head">
                <h2>{p.title}</h2>
                <Badge kind={p.masteredCount > 0 ? 'good' : 'soft'}>
                  {p.masteredCount}/{p.totalCount} 精熟
                </Badge>
              </div>
              {p.goal && <p className="plan-goal">{p.goal}</p>}

              {p.corpusName && (
                <div className="plan-corpus">
                  <span className="eyebrow">资料</span>
                  <span className="corpus-name">{p.corpusName}</span>
                </div>
              )}

              <div className="plan-toolbar">
                <Button variant="primary" onClick={() => onContinue(p.id)}>
                  <Play size={15} strokeWidth={1.8} /> 继续学习
                </Button>
                <Button
                  variant="ghost"
                  onClick={() => onReview(p.id)}
                  disabled={p.dueReviewCount === 0}
                  title={p.dueReviewCount === 0 ? '暂无到期复习项' : `有 ${p.dueReviewCount} 个到期复习项`}
                >
                  <RefreshCw size={15} strokeWidth={1.8} /> 复习
                  {p.dueReviewCount > 0 && <span className="due-badge">{p.dueReviewCount}</span>}
                </Button>
              </div>

              <div className="plan-concepts">
                {p.concepts.length === 0 ? (
                  <span className="eyebrow">暂无知识点，先去练「系统帮我选」</span>
                ) : (
                  p.concepts.map((c) => (
                    <button className="concept-chip" key={c.id} onClick={() => onPick(c.id)}>
                      <span className="chip-name">{c.name}</span>
                      <Tag>L{c.layer}</Tag>
                      {c.masteryLevel > 0 && <span className="chip-done">✓</span>}
                    </button>
                  ))
                )}
              </div>
            </Card>
          ))}
        </div>
      )}
    </div>
  );
}
