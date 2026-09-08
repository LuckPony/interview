import { useEffect, useState } from 'react';
import { Database, RefreshCw } from 'lucide-react';
import { Link } from 'react-router-dom';
import { corpus } from '../api/drill';
import { INDEX_LABELS, libraryError } from '../api/library';
import type { CorpusView } from '../api/types';
import '../pages/KnowledgeBasePage.css';

/** 学习计划和模拟面试共用资料选择器，不重复上传已入库文件。 */
export function CorpusPicker({ value, onChange, disabled = false }: {
  value: number | null; onChange: (value: CorpusView | null) => void; disabled?: boolean;
}) {
  const [items, setItems] = useState<CorpusView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    let alive = true;
    setLoading(true); setError('');
    corpus.list().then(data => { if (alive) setItems(data); })
      .catch(e => { if (alive) setError(libraryError(e)); }).finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
  }, [revision]);
  const selected = items.find(item => item.id === value);
  return <div className="kb-picker">
    <label><span><Database size={15} />从知识库选择资料</span>
      <select aria-label="从知识库选择资料" value={value ?? ''} disabled={disabled || loading}
        onChange={event => onChange(items.find(item => item.id === Number(event.target.value)) ?? null)}>
        <option value="">{loading ? '读取知识库…' : '不使用知识库资料'}</option>
        {value && !selected && <option value={value}>当前附加资料 #{value}</option>}
        {items.map(item => <option key={item.id} value={item.id}>{item.name} · {INDEX_LABELS[item.indexState ?? 'PENDING']}</option>)}
      </select>
    </label>
    {selected && <p>{selected.overview || '将使用这份资料的章节索引与原文摘录作为生成依据。'}</p>}
    {error && <p role="alert">{error} <button type="button" onClick={() => setRevision(x => x + 1)}><RefreshCw size={12} />重试</button></p>}
    {!loading && !items.length && !error && <p>还没有资料，先去<Link to="/knowledge-base">知识库导入</Link>。</p>}
  </div>;
}
