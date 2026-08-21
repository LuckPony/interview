import { useState } from 'react';
import { Markdown } from './Markdown';
import './MarkdownEditor.css';

interface MarkdownEditorProps {
  value: string;
  onChange: (value: string) => void;
  rows?: number;
  placeholder?: string;
  /** 预览区最小高度，避免「写/预览」切换时布局跳动 */
  minPreviewHeight?: number;
}

/**
 * 轻量 Markdown 编辑器：一个文本框 + 「写 / 预览」切换。
 * 预览复用项目已有的 <Markdown>（react-markdown + GFM + 代码高亮），
 * 不额外引入编辑器依赖，保持体积与安全策略（不渲染原始 HTML）一致。
 */
export function MarkdownEditor({
  value,
  onChange,
  rows = 8,
  placeholder,
  minPreviewHeight = 180,
}: MarkdownEditorProps) {
  const [tab, setTab] = useState<'write' | 'preview'>('write');

  return (
    <div className="md-editor">
      <div className="md-editor-tabs" role="tablist" aria-label="Markdown 编辑器">
        <button
          type="button"
          role="tab"
          aria-selected={tab === 'write'}
          className={`md-editor-tab${tab === 'write' ? ' is-on' : ''}`}
          onClick={() => setTab('write')}
        >
          写
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={tab === 'preview'}
          className={`md-editor-tab${tab === 'preview' ? ' is-on' : ''}`}
          onClick={() => setTab('preview')}
        >
          预览
        </button>
        <span className="md-editor-hint">支持 Markdown · 代码用 ```语言 包裹</span>
      </div>

      {tab === 'write' ? (
        <textarea
          className="md-editor-area"
          rows={rows}
          placeholder={placeholder}
          value={value}
          onChange={(e) => onChange(e.target.value)}
        />
      ) : (
        <div className="md-editor-preview" style={{ minHeight: minPreviewHeight }}>
          {value.trim() ? (
            <Markdown>{value}</Markdown>
          ) : (
            <span className="md-editor-empty">（还没有内容，切回「写」开始输入）</span>
          )}
        </div>
      )}
    </div>
  );
}
