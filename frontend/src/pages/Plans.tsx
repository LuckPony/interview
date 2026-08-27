import { useEffect, useState } from 'react';
import { Play, RefreshCw, Shuffle, CalendarClock, CheckCircle2, BookOpen, RotateCcw } from 'lucide-react';
import { drill } from '../api/drill';
import { Button, Card, Tag, Badge } from '../components/ui';
import { useActivePlan } from '../lib/useActivePlan';
import type { PlanView, DailyTaskView } from '../api/types';

/** /drill 的入口态：列我的学习方向，继续/复习/点概念开练，或新建方向。 */
export function Plans({
  plans,
  onPick,
  onPickSub,
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
  /** 点击某个子知识点直接开练（子知识点直通）：概念 + 子点名 + 子点序号 + 所属方向 */
  onPickSub?: (conceptId: number, subPoint: string, subIndex: number, planId?: number) => void;
  onContinue: (planId: number) => void;
  onReview: (planId: number) => void;
  onFree?: () => void;
  onStartTask: (taskId: number) => void;
  err: string;
  teachFirst: boolean;
  onToggleTeachFirst: (v: boolean) => void;
}) {
  // 今日任务（每日自动排期 + 预生成，点开即答）；展示账号下所有学习方向，避免漏掉其他方向的到期复习
  const [today, setToday] = useState<DailyTaskView[] | null>(null);
  useEffect(() => {
    let alive = true;
    drill
      .today()
      .then((t) => {
        if (alive) {
          setToday(t);
          const active = t.filter((x) => x.status !== 'DONE' && x.status !== 'SKIPPED');
          window.electronAPI?.updateReminderTasks?.({
            learn: active.filter((x) => x.kind === 'NEW').length,
            review: active.filter((x) => x.kind === 'REVIEW').length,
          }).catch(() => {});
        }
      })
      .catch(() => {
        if (alive) setToday([]);
      });
    return () => {
      alive = false;
    };
  }, []);

  // 今日任务属于整个账号，不再按首页的当前方向过滤。否则切换方向后，其他方向的到期复习会被隐藏。
  const { activeId } = useActivePlan(plans);
  const todayTasks = today ?? [];
  const todayActive = todayTasks.filter((t) => t.status !== 'DONE' && t.status !== 'SKIPPED');
  const todayDone = todayTasks.filter((t) => t.status === 'DONE');
  const planList = plans.filter((p) => p.id === activeId);
  const reviewTasks = todayActive.filter((t) => t.kind === 'REVIEW');
  const learnTasks = todayActive.filter((t) => t.kind === 'NEW');
  const conceptFor = (task: DailyTaskView) => plans
    .flatMap((plan) => plan.concepts)
    .find((concept) => concept.id === task.conceptId);

  const renderTask = (t: DailyTaskView) => {
    const concept = conceptFor(t);
    const subPoints = concept?.subPoints ?? [];
    const completed = new Set(concept?.completedSubPoints ?? []);
    const target = t.kind === 'NEW' ? subPoints.find((point) => !completed.has(point)) : undefined;
    return (
      <button className={`today-task-card ${t.kind === 'REVIEW' ? 'is-review' : 'is-learn'}`} key={t.id} onClick={() => onStartTask(t.id)}>
        <span className="today-task-top">
          <Badge kind={t.kind === 'REVIEW' ? 'warn' : 'good'}>{t.kind === 'REVIEW' ? '待复习' : '待学习'}</Badge>
          <span className="today-task-plan">{t.planTitle}</span>
          <Play size={14} strokeWidth={1.8} className="today-arrow" />
        </span>
        <strong className="today-task-concept">{t.conceptName}<em>L{t.layer}</em></strong>
        {t.kind === 'NEW' ? (
          <span className="today-task-target">
            <BookOpen size={14} />
            {target ? <>下一子知识点：<b>{target}</b></> : '子知识点已学完，将进入综合检测'}
          </span>
        ) : (
          <span className="today-task-target">
            <RotateCcw size={14} />
            {t.subPoint ? <>聚焦复习：<b>{t.subPoint}</b></> : '复习该知识点的关键内容'}
          </span>
        )}
        {subPoints.length > 0 && (
          <span className="today-subpoints">
            {subPoints.map((point) => (
              <span className={completed.has(point) ? 'is-passed' : ''} key={point}>
                {completed.has(point) ? '✓ ' : ''}{point}
              </span>
            ))}
          </span>
        )}
        <span className="today-task-hint">{t.stem ? t.stem.slice(0, 72) : t.kind === 'NEW' ? '点击进入教学与练习' : '复习题生成中，点击后会自动准备'}</span>
      </button>
    );
  };

  return (
    <div className="page">
      <header className="page-head">
        <span className="eyebrow">练习 · 快速开始</span>
        <h1>今日学习</h1>
        <p>按今天安排的复习和新学逐项完成；“继续学习”会自动进入当前 L 层的下一个子知识点。</p>
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

      {plans.length > 0 && (
        <Card className="today-card">
          <div className="today-head">
            <CalendarClock size={17} strokeWidth={1.7} />
            <span className="today-title">今日待办</span>
            <span className="today-count">
              <span className="today-kind today-review">复习 {reviewTasks.length}</span>
              <span className="today-kind today-new">学习 {learnTasks.length}</span>
            </span>
          </div>
          {today === null ? (
            <p className="today-empty">正在安排今天的学习和复习任务…</p>
          ) : todayActive.length === 0 ? (
            <p className="today-empty">今天的学习和复习任务已经全部完成。</p>
          ) : (
            <div className="today-groups">
              <section className="today-group">
                <div className="today-group-head"><BookOpen size={16} /><strong>要学习</strong><span>{learnTasks.length} 项</span></div>
                <div className="today-task-grid">
                  {learnTasks.length > 0 ? learnTasks.map(renderTask) : <p className="today-empty">今天没有新的学习任务。</p>}
                </div>
              </section>
              <section className="today-group">
                <div className="today-group-head"><RotateCcw size={16} /><strong>要复习</strong><span>{reviewTasks.length} 项</span></div>
                <div className="today-task-grid">
                  {reviewTasks.length > 0 ? reviewTasks.map(renderTask) : <p className="today-empty">当前没有到期复习任务。</p>}
                </div>
              </section>
            </div>
          )}
          {todayDone.length > 0 && <p className="today-completed-note"><CheckCircle2 size={14} /> 今日已完成 {todayDone.length} 项</p>}
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

              <div className="plan-concepts current-learning">
                {(() => {
                  const pending = p.concepts
                    .slice()
                    .sort((a, b) => a.layer - b.layer || a.id - b.id)
                    .find((c) => {
                      const sub = c.subPoints ?? [];
                      const done = c.completedSubPoints ?? [];
                      return sub.length === 0 || done.length < sub.length;
                    });
                  if (!pending) return <span className="eyebrow">子知识点已完成，继续学习将进入综合检测</span>;
                  const doneSet = new Set(pending.completedSubPoints ?? []);
                  const subs = pending.subPoints ?? [];
                  const nextIdx = subs.findIndex((s) => !doneSet.has(s));
                  return (
                    <div className="current-subpoints">
                      <button
                        className="concept-chip current-concept-chip"
                        onClick={() => onPick(pending.id)}
                        title={`进入「${pending.name}」的完整教考流程（先看全部子知识点）`}
                      >
                        <span className="chip-name">正在学习：{pending.name}</span>
                        <Tag>L{pending.layer}</Tag>
                      </button>
                      {subs.length === 0 ? (
                        <span className="eyebrow">点上面的知识点，进入后会自动拆解成子知识点再逐个练习</span>
                      ) : (
                        <div className="subpoint-chips">
                          {subs.map((sp, i) => {
                            const passed = doneSet.has(sp);
                            const isNext = i === nextIdx;
                            return (
                              <button
                                key={`${sp}-${i}`}
                                className={'subpoint-chip' + (passed ? ' is-passed' : '') + (isNext ? ' is-next' : '')}
                                onClick={() => onPickSub?.(pending.id, sp, i, p.id)}
                                title={passed ? `重新练习子知识点「${sp}」` : `练习子知识点「${sp}」`}
                              >
                                {passed && <span aria-hidden>✓</span>}
                                {sp}
                              </button>
                            );
                          })}
                        </div>
                      )}
                    </div>
                  );
                })()}
              </div>
            </Card>
          ))}
        </div>
      )}
    </div>
  );
}
