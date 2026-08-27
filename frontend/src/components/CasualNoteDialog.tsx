import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import MDEditor, { getCommands, type ICommand } from '@uiw/react-md-editor';
import '@uiw/react-md-editor/markdown-editor.css';
import '@uiw/react-markdown-preview/markdown.css';
import { Search, Plus, Trash2, X, Clock, ImagePlus } from 'lucide-react';
import { Button } from './ui';
import { knowledgeApi } from '../api/knowledge';
import { ApiError } from '../api/client';
import type { CasualNote } from '../api/types';
import './CasualNoteDialog.css';

function msg(e: unknown): string {
  return e instanceof ApiError ? e.message : '操作失败';
}

/**
 * 替换 @uiw/react-md-editor 默认 image 命令（默认只插占位符 ![image](url)）：
 * 点按钮 → 选本地图片 → FileReader 转 base64 → 以 ![](data:…) 插入光标处。
 * 图片随 markdown 正文一起存库（casual_note.content），预览可直接渲染。
 */
const imageCommand: ICommand = {
  name: 'image',
  keyCommand: 'image',
  buttonProps: {
    'aria-label': '插入图片',
    title: '插入图片（选本地图片，存为 base64）',
  },
  icon: <ImagePlus size={15} strokeWidth={1.8} />,
  execute: (_state, api) => {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = 'image/*';
    input.onchange = () => {
      const file = input.files?.[0];
      if (!file) return;
      const reader = new FileReader();
      reader.onload = () => {
        const dataUrl = String(reader.result);
        if (dataUrl) api.replaceSelection(`![](${dataUrl})`);
      };
      reader.readAsDataURL(file);
    };
    input.click();
  },
};

/** 默认工具栏，但图片按钮换成上面支持本地文件转 base64 的版本。 */
function toolbarCommands(): ICommand[] {
  return getCommands().map((c) => (c.name === 'image' ? imageCommand : c));
}

function shortDate(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  const now = new Date();
  const sameDay = d.toDateString() === now.toDateString();
  return sameDay
    ? d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
    : d.toLocaleDateString('zh-CN', { month: 'numeric', day: 'numeric' });
}

type Props = {
  onClose: () => void;
  onSaved?: () => void;
};

/**
 * 随手记弹窗（两栏）：
 * 左侧 = 该用户的全部历史随手记（搜索 / 点击载入编辑 / 删除）；
 * 右侧 = 标题 + Markdown 编辑器（@uiw/react-md-editor 现成工具栏：
 * 加粗/斜体/标题/引用/代码/链接/表格/列表/图片…，支持粘贴与拖入图片，自动转 base64 存库）。
 * 保存后留在弹窗里继续编辑，关闭才退出。
 */
export function CasualNoteDialog({ onClose, onSaved }: Props) {
  const [notes, setNotes] = useState<CasualNote[] | null>(null);
  const [search, setSearch] = useState('');
  const [editingId, setEditingId] = useState<number | null>(null);
  const [title, setTitle] = useState('');
  const [content, setContent] = useState('');
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);
  const [savedFlash, setSavedFlash] = useState(false);
  const mainRef = useRef<HTMLDivElement>(null);
  const mdRef = useRef<React.ElementRef<typeof MDEditor>>(null);
  const editorWrapRef = useRef<HTMLDivElement>(null);

  /** 把图片文件转 base64 并以 ![](data:…) 插入光标处。 */
  const insertImage = useCallback((file: File) => {
    if (!file.type.startsWith('image/')) return;
    const reader = new FileReader();
    reader.onload = () => {
      const dataUrl = String(reader.result);
      if (dataUrl) {
        mdRef.current?.commandOrchestrator?.textApi.replaceSelection(`![](${dataUrl})`);
      }
    };
    reader.readAsDataURL(file);
  }, []);

  // 粘贴 / 拖入图片 → 转 base64 插入（@uiw 内置 image 命令不处理文件）
  useEffect(() => {
    const el = editorWrapRef.current;
    if (!el) return;
    const firstImage = (files: FileList | null): File | null => {
      for (const f of Array.from(files ?? [])) {
        if (f.type.startsWith('image/')) return f;
      }
      return null;
    };
    const onPaste = (e: ClipboardEvent) => {
      const img = firstImage(e.clipboardData?.files ?? null);
      if (!img) return;
      e.preventDefault();
      insertImage(img);
    };
    const onDrop = (e: DragEvent) => {
      const img = firstImage(e.dataTransfer?.files ?? null);
      if (!img) return;
      e.preventDefault();
      insertImage(img);
    };
    el.addEventListener('paste', onPaste);
    el.addEventListener('drop', onDrop);
    return () => {
      el.removeEventListener('paste', onPaste);
      el.removeEventListener('drop', onDrop);
    };
  }, [insertImage]);

  const loadNotes = useCallback(async () => {
    try {
      setNotes(await knowledgeApi.listNotes());
    } catch (e) {
      setErr(msg(e));
      setNotes([]);
    }
  }, []);

  useEffect(() => {
    void loadNotes();
  }, [loadNotes]);

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    const base = notes ?? [];
    if (!q) return base;
    return base.filter(
      (n) =>
        (n.title || '').toLowerCase().includes(q) ||
        (n.content || '').toLowerCase().includes(q),
    );
  }, [notes, search]);

  const openNew = () => {
    setEditingId(null);
    setTitle('');
    setContent('');
    setErr('');
    // 等编辑器渲染后再聚焦 textarea
    requestAnimationFrame(() => mainRef.current?.querySelector('textarea')?.focus());
  };

  const openNote = (n: CasualNote) => {
    setEditingId(n.id);
    setTitle(n.title ?? '');
    setContent(n.content ?? '');
    setErr('');
  };

  const removeNote = async (n: CasualNote) => {
    if (!window.confirm(`确定删除「${n.title || '无标题'}」吗？此操作不可恢复。`)) return;
    setErr('');
    try {
      await knowledgeApi.deleteNote(n.id);
      if (editingId === n.id) openNew();
      await loadNotes();
    } catch (e) {
      setErr(msg(e));
    }
  };

  const save = async () => {
    if (!content.trim()) {
      setErr('内容不能为空');
      return;
    }
    setBusy(true);
    setErr('');
    try {
      const saved = editingId != null
        ? await knowledgeApi.updateNote(editingId, { title: title.trim(), content })
        : await knowledgeApi.createNote({
            title: title.trim(),
            content,
          });
      setEditingId(saved.id);
      onSaved?.();
      setSavedFlash(true);
      window.setTimeout(() => setSavedFlash(false), 1600);
      await loadNotes();
    } catch (e) {
      setErr(msg(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="casual-note-backdrop">
      <div className="casual-note-dialog" role="dialog" aria-modal="true" aria-label="随手记">
        <header className="casual-note-head">
          <div className="casual-note-head-title">
            <h2>随手记</h2>
            <span className="casual-note-sub">Markdown 语法 · 工具栏一键插入 · 支持粘贴 / 拖入图片</span>
          </div>
          <button className="close-btn" onClick={onClose} aria-label="关闭">
            <X size={16} strokeWidth={1.8} />
          </button>
        </header>

        <div className="casual-note-body">
          {/* —— 左侧：历史随手记列表 —— */}
          <aside className="casual-note-side">
            <div className="casual-note-side-tools">
              <div className="casual-note-search">
                <Search size={14} strokeWidth={1.8} />
                <input
                  placeholder="搜索标题 / 内容…"
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                />
              </div>
              <button className="casual-note-new" onClick={openNew} title="新建随手记" aria-label="新建">
                <Plus size={16} strokeWidth={2} />
              </button>
            </div>
            <div className="casual-note-list">
              {notes === null ? (
                <div className="casual-note-empty">加载中…</div>
              ) : filtered.length === 0 ? (
                <div className="casual-note-empty">
                  {search ? '没有匹配的随手记' : '还没有随手记，点右上角 + 新建'}
                </div>
              ) : (
                filtered.map((n) => {
                  const active = editingId === n.id;
                  const firstLine = (n.content || '').split('\n').find((l) => l.trim()) ?? '';
                  return (
                    <div
                      key={n.id}
                      className={'casual-note-item' + (active ? ' is-active' : '')}
                      onClick={() => openNote(n)}
                    >
                      <div className="casual-note-item-head">
                        <span className="casual-note-item-title">{n.title || '（无标题）'}</span>
                        <button
                          className="casual-note-item-del"
                          title="删除"
                          aria-label="删除"
                          onClick={(e) => {
                            e.stopPropagation();
                            void removeNote(n);
                          }}
                        >
                          <Trash2 size={13} strokeWidth={1.8} />
                        </button>
                      </div>
                      <p className="casual-note-item-snippet">{firstLine || '（空内容）'}</p>
                      <span className="casual-note-item-time">
                        <Clock size={11} strokeWidth={1.8} /> {shortDate(n.createdAt)}
                      </span>
                    </div>
                  );
                })
              )}
            </div>
          </aside>

          {/* —— 右侧：标题 + Markdown 编辑器 —— */}
          <main className="casual-note-main" ref={mainRef}>
            {err && <div className="casual-note-banner">{err}</div>}
            <input
              className="casual-note-title-input"
              placeholder="标题（可选，留空默认取内容首行）"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
            />
            <div className="casual-note-editor" data-color-mode="light" ref={editorWrapRef}>
              <MDEditor
                ref={mdRef}
                value={content}
                onChange={(v) => setContent(v ?? '')}
                height="100%"
                preview="live"
                commands={toolbarCommands()}
                textareaProps={{
                  placeholder: '用 Markdown 写点什么…（工具栏一键插入图片，或直接粘贴 / 拖入图片）',
                }}
              />
            </div>
            {savedFlash && <div className="casual-note-saved">已保存 ✓</div>}
            <div className="casual-note-actions">
              <span className="casual-note-hint">
                {editingId != null ? '正在编辑已有笔记' : '新建随手记'}
              </span>
              <Button variant="ghost" onClick={onClose}>关闭</Button>
              <Button onClick={save} disabled={busy}>{busy ? '保存中…' : '保存'}</Button>
            </div>
          </main>
        </div>
      </div>
    </div>
  );
}