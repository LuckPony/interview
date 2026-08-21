import { useEffect, useState } from 'react';
import { Check, AlertTriangle } from 'lucide-react';
import { drill } from '../api/drill';
import { Button, Badge } from './ui';
import { MarkdownEditor } from './MarkdownEditor';
import { ApiError } from '../api/client';
import type { NoteView } from '../api/types';
import './NoteDialog.css';

function msg(e: unknown): string {
  return e instanceof ApiError ? e.message : '操作失败';
}

interface NoteDialogProps {
  runId: number | null;
  stem?: string;
  onClose: () => void;
  onSaved?: () => void;
}

export function NoteDialog({ runId, stem, onClose, onSaved }: NoteDialogProps) {
  const [myWords, setMyWords] = useState('');
  const [gapFound, setGapFound] = useState('');
  const [nextAction, setNextAction] = useState('');
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState<NoteView | null>(null);

  // 每次打开新的一道时，清空表单
  useEffect(() => {
    if (runId != null) {
      setMyWords('');
      setGapFound('');
      setNextAction('');
      setErr('');
      setBusy(false);
      setDone(null);
    }
  }, [runId]);

  // Esc 关闭（遮罩层也可点，但表单区不冒泡）
  useEffect(() => {
    if (runId == null) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [runId, onClose]);

  if (runId == null) return null;

  const submit = async () => {
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
      const nv = await drill.note(runId, { myWords, gapFound, nextAction });
      setDone(nv);
    } catch (e) {
      setErr(msg(e)); // 后端 422：抄写 / 缺口未填
    } finally {
      setBusy(false);
    }
  };

  const finish = () => {
    onSaved?.();
    onClose();
  };

  return (
    <div className="note-backdrop" onClick={onClose}>
      <div
        className="note-dialog"
        role="dialog"
        aria-modal="true"
        aria-label="写内化笔记"
        onClick={(e) => e.stopPropagation()}
      >
        <header className="note-dialog-head">
          <span className="eyebrow">内化笔记 · NOTE</span>
          <h2>把这道题嚼碎</h2>
          <p className="note-dialog-sub">
            笔记里没有「标准答案」可抄——只有你自己的话，才叫内化。
          </p>
        </header>

        {done ? (
          <div className="note-done">
            <div className="done-icon">
              <Check size={34} strokeWidth={1.5} />
            </div>
            <h3>已内化</h3>
            <Overlap ratio={done.overlapRatio} />
            <p className="done-left">还剩 {done.debtLeft} 条欠账</p>
            <div className="note-actions">
              <Button onClick={finish}>完成</Button>
            </div>
          </div>
        ) : (
          <>
            {stem && (
              <div className="note-q">
                <Badge kind="bad">本题</Badge>
                <span className="note-stem">{stem}</span>
              </div>
            )}

            {err && <div className="banner info">{err}</div>}

            <label className="field">
              <span className="field-label">用自己的话复述（支持 Markdown）</span>
              <MarkdownEditor
                rows={6}
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
              <Button variant="ghost" onClick={onClose}>
                取消
              </Button>
              <Button onClick={submit} disabled={busy}>
                {busy ? '提交中…' : '保存笔记'}
              </Button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}

function Overlap({ ratio }: { ratio: number }) {
  const pct = Math.round(ratio * 100);
  const high = ratio > 0.35;
  return (
    <div className={'overlap' + (high ? ' high' : '')}>
      {high ? (
        <AlertTriangle size={16} strokeWidth={1.6} />
      ) : (
        <Check size={16} strokeWidth={1.6} />
      )}
      <span>
        这段有 <strong>{pct}%</strong> 来自题干或评分点
        {high ? '，自己重写更有用。' : '，基本是你自己的话。'}
      </span>
    </div>
  );
}
