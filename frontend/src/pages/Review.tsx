import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, Trash2 } from 'lucide-react';
import { drill } from '../api/drill';
import { Card, Button, Badge, Loading } from '../components/ui';
import { Markdown } from '../components/Markdown';
import { MarkdownEditor } from '../components/MarkdownEditor';
import { ApiError } from '../api/client';
import type { ReviewView } from '../api/types';
import './Notes.css';

function msg(e: unknown): string {
  return e instanceof ApiError ? e.message : '操作失败';
}

/** 复盘子页面：题目 + AI 复盘报告（欠缺/思路/口诀）+ 自省笔记（Markdown 写 + 预览）。 */
export function ReviewPage() {
  const { runId } = useParams();
  const navigate = useNavigate();
  const [review, setReview] = useState<ReviewView | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState('');

  const [myWords, setMyWords] = useState('');
  const [gapFound, setGapFound] = useState('');
  const [nextAction, setNextAction] = useState('');
  const [busy, setBusy] = useState(false);
  const [deleting, setDeleting] = useState(false);

  const load = (id: number) => {
    setLoading(true);
    drill
      .review(id)
      .then((rv) => {
        setReview(rv);
        // 已有笔记则回填编辑区（读态展示；无笔记时是空表单）
        setMyWords(rv.myWords ?? '');
        setGapFound(rv.gapFound ?? '');
        setNextAction(rv.nextAction ?? '');
      })
      .catch((e) => setErr(msg(e)))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    const id = Number(runId);
    if (!id) {
      setErr('参数错误');
      setLoading(false);
      return;
    }
    load(id);
  }, [runId]);

  /** 删除这条作答记录（级联删除判分/复盘/笔记），删除前二次确认 */
  const del = async () => {
    if (!review) return;
    if (!window.confirm(
      '确定删除这条作答记录？\n\n它的判分、AI 复盘与笔记都会被删除，且不可恢复。',
    )) return;
    setDeleting(true);
    setErr('');
    try {
      await drill.deleteRun(review.runId);
      navigate('/notes');
    } catch (e) {
      setErr(msg(e));
      setDeleting(false);
    }
  };

  const submit = async () => {
    if (!review) return;
    if (myWords.trim().length < 10) {
      setErr('用自己的话多写几句，至少 10 个字。');
      return;
    }
    if (!gapFound.trim()) {
      setErr('先说清楚这次暴露出的缺口在哪。');
      return;
    }
    setBusy(true);
    setErr('');
    try {
      await drill.note(review.runId, { myWords, gapFound, nextAction });
      await load(review.runId); // 重新拉取 → 切换为已保存的读态
    } catch (e) {
      setErr(msg(e));
    } finally {
      setBusy(false);
    }
  };

  const hasNote = review != null && review.myWords != null;

  return (
    <div className="page">
      <header className="page-head">
        <span className="eyebrow">内化复盘</span>
        <h1>复盘</h1>
        <button className="head-back" onClick={() => navigate('/notes')}>
          <ArrowLeft size={14} strokeWidth={1.6} /> 返回内化复盘
        </button>
      </header>

      {err && <div className="banner info">{err}</div>}

      {loading ? (
        <Loading label="读取复盘…" />
      ) : !review ? null : (
        <div className="review-page">
          {/* 题目 + 薄弱点（markdown 渲染） */}
          <Card className="review-question">
            <div className="note-q">
              <Badge kind="bad">{Math.round(review.rawScore)} 分</Badge>
              <Markdown className="note-stem">{review.stem}</Markdown>
            </div>
            {(review.weakPoints?.length ?? 0) > 0 && (
              <div className="weak-panel">
                <span className="eyebrow">薄弱点清单 · 判分未打中</span>
                <ul className="weak-list">
                  {(review.weakPoints ?? []).map((p, i) => (
                    <li key={i}>
                      <span className="weak-dot" />
                      <Markdown className="weak-md">{p}</Markdown>
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </Card>

          {/* AI 复盘报告：欠缺 / 思路 / 口诀（markdown 渲染） */}
          <div className="review-report">
            <div className="review-section">
              <span className="review-label">你的欠缺 · 对话总结</span>
              <Markdown className="review-text">{review.gapSummary ?? ''}</Markdown>
            </div>
            <div className="review-section">
              <span className="review-label">解题思路</span>
              <Markdown className="review-text">{review.approach ?? ''}</Markdown>
            </div>
            <div className="review-section mnemonic">
              <span className="review-label">记忆口诀</span>
              <Markdown className="review-mnemonic">{review.mnemonic ?? ''}</Markdown>
            </div>
          </div>

          {/* 自省笔记：已保存 → 只读展示；未保存 → Markdown 表单 */}
          {hasNote ? (
            <Card className="note-form note-saved">
              <div className="note-saved-head">
                <span className="eyebrow">你的自省</span>
                <Badge kind="good">已内化</Badge>
              </div>
              <div className="note-saved-body">
                <Markdown>{review.myWords ?? ''}</Markdown>
              </div>
              {review.gapFound && (
                <div className="note-saved-meta">
                  <span className="field-label">这次暴露的缺口</span>
                  <p>{review.gapFound}</p>
                </div>
              )}
              {review.nextAction && (
                <div className="note-saved-meta">
                  <span className="field-label">下一步</span>
                  <p>{review.nextAction}</p>
                </div>
              )}
              <div className="note-actions">
                <Button variant="ghost" onClick={() => navigate('/notes')}>
                  返回内化复盘
                </Button>
              </div>
            </Card>
          ) : (
            <Card className="note-form">
              <span className="eyebrow">你的自省</span>
              <label className="field">
                <span className="field-label">用自己的话复述（支持 Markdown）</span>
                <MarkdownEditor
                  rows={9}
                  placeholder={'关上资料，凭记忆写。抄题干会被拦下。\n支持 Markdown：```python ... ``` 贴代码、**加粗**、- 列表。'}
                  value={myWords}
                  onChange={setMyWords}
                />
              </label>
              <label className="field">
                <span className="field-label">这次暴露的缺口</span>
                <textarea
                  className="note-area"
                  rows={2}
                  placeholder="哪一点没答上、哪点讲错了？"
                  value={gapFound}
                  onChange={(e) => setGapFound(e.target.value)}
                />
              </label>
              <label className="field">
                <span className="field-label">下一步怎么补（可留空）</span>
                <input
                  className="note-input"
                  placeholder="例如：重看 JVM 内存模型一节"
                  value={nextAction}
                  onChange={(e) => setNextAction(e.target.value)}
                />
              </label>
              <div className="note-actions">
                <Button variant="ghost" onClick={() => navigate('/notes')}>
                  取消
                </Button>
                <Button onClick={submit} disabled={busy}>
                  {busy ? '保存中…' : '保存笔记'}
                </Button>
              </div>
            </Card>
          )}

          {/* 删除这条记录：清掉判分/复盘/笔记，不可恢复 */}
          <div className="review-delete">
            <span className="review-delete-hint">
              不要这条记录了？删除后判分、AI 复盘与笔记一并清除，且不可恢复。
            </span>
            <Button variant="danger" onClick={del} disabled={deleting}>
              <Trash2 size={14} strokeWidth={1.8} /> {deleting ? '删除中…' : '删除这条记录'}
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}
