import { useCallback, useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import {
  BookOpenCheck,
  ChevronDown,
  Database,
  FileText,
  FolderUp,
  Loader2,
  Plus,
  RefreshCw,
  Sparkles,
  Trash2,
} from 'lucide-react';
import { ApiError } from '../api/client';
import { corpus } from '../api/drill';
import type { CorpusView, KnowledgePointsView } from '../api/types';
import { Button, Card, Loading } from '../components/ui';
import './KnowledgeBasePage.css';

function errorMessage(error: unknown): string {
  if (error instanceof ApiError || error instanceof Error) return error.message;
  return '操作失败，请稍后重试';
}

function formatChars(value: number): string {
  if (value < 1000) return `${value} 字`;
  return `${(value / 1000).toFixed(value >= 10_000 ? 0 : 1)}k 字`;
}

function formatDate(value?: string | null): string {
  if (!value) return '刚刚导入';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '日期未知';
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(date);
}

export function KnowledgeBasePage() {
  const location = useLocation();
  const navigate = useNavigate();
  const importMode = location.pathname.endsWith('/import');
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [items, setItems] = useState<CorpusView[]>([]);
  const [loading, setLoading] = useState(true);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState('');
  const [expandedId, setExpandedId] = useState<number | null>(null);
  const [pointState, setPointState] = useState<Record<number, KnowledgePointsView>>({});
  const [pointLoading, setPointLoading] = useState<number | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      setItems(await corpus.list());
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  useEffect(() => {
    if (importMode) {
      window.setTimeout(() => fileInputRef.current?.focus(), 80);
    }
  }, [importMode]);

  const upload = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file) return;
    setUploading(true);
    setError('');
    try {
      const created = await corpus.upload(file);
      setItems(current => [created, ...current.filter(item => item.id !== created.id)]);
      setExpandedId(created.id);
      navigate('/knowledge-base', { replace: true });
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setUploading(false);
    }
  };

  const togglePoints = async (id: number) => {
    if (expandedId === id) {
      setExpandedId(null);
      return;
    }
    setExpandedId(id);
    if (pointState[id]) return;
    setPointLoading(id);
    setError('');
    try {
      const result = await corpus.knowledgePoints(id);
      setPointState(current => ({ ...current, [id]: result }));
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setPointLoading(null);
    }
  };

  const refreshPoints = async (id: number) => {
    setPointLoading(id);
    setError('');
    try {
      const result = await corpus.knowledgePoints(id);
      setPointState(current => ({ ...current, [id]: result }));
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setPointLoading(null);
    }
  };

  const remove = async (item: CorpusView) => {
    if (!window.confirm(`确定删除知识资料「${item.name}」？已用于学习计划的资料会被保护，不允许误删。`)) return;
    setError('');
    try {
      await corpus.remove(item.id);
      setItems(current => current.filter(entry => entry.id !== item.id));
      if (expandedId === item.id) setExpandedId(null);
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  return (
    <div className="page knowledge-page">
      <header className="page-head knowledge-head">
        <div>
          <span className="eyebrow">知识管理 · KNOWLEDGE</span>
          <h1>{importMode ? '导入学习资料' : '知识库管理'}</h1>
          <p>集中管理用于学习规划和 AI 出题的资料，并查看系统从资料中提取出的知识点。</p>
        </div>
        {!importMode && (
          <Button onClick={() => navigate('/knowledge-base/import')}>
            <Plus size={16} /> 导入资料
          </Button>
        )}
      </header>

      {error && <div className="banner">{error}</div>}

      <Card className={`knowledge-import-card${importMode ? ' is-emphasized' : ''}`}>
        <span className="knowledge-import-icon"><FolderUp size={25} /></span>
        <div className="knowledge-import-copy">
          <h2>{uploading ? '正在解析资料…' : '把资料加入你的知识库'}</h2>
          <p>支持 PDF、TXT、Markdown 和 Word。导入后会自动拆分内容并提取候选知识点。</p>
        </div>
        <label className={`knowledge-upload-button${uploading ? ' disabled' : ''}`}>
          {uploading ? <Loader2 className="spin" size={16} /> : <FolderUp size={16} />}
          {uploading ? '解析中…' : '选择文件'}
          <input
            ref={fileInputRef}
            type="file"
            accept=".pdf,.txt,.md,.markdown,.mdx,.docx"
            onChange={upload}
            disabled={uploading}
          />
        </label>
      </Card>

      <div className="knowledge-summary">
        <div><Database size={18} /><strong>{items.length}</strong><span>份资料</span></div>
        <div><FileText size={18} /><strong>{formatChars(items.reduce((sum, item) => sum + item.charCount, 0))}</strong><span>已解析内容</span></div>
        <div><Sparkles size={18} /><strong>自动提取</strong><span>知识点索引</span></div>
      </div>

      <section className="knowledge-list-section">
        <div className="knowledge-section-head">
          <div>
            <span className="eyebrow">LIBRARY</span>
            <h2>已导入资料</h2>
          </div>
          <button type="button" className="knowledge-refresh" onClick={() => void load()} disabled={loading}>
            <RefreshCw size={15} className={loading ? 'spin' : ''} /> 刷新
          </button>
        </div>

        {loading ? (
          <Loading label="读取知识库…" />
        ) : items.length === 0 ? (
          <div className="empty knowledge-empty">
            <BookOpenCheck size={34} strokeWidth={1.4} />
            <h3>知识库还是空的</h3>
            <p>先导入一份学习资料，它可以在新建学习方向时直接复用。</p>
          </div>
        ) : (
          <div className="knowledge-list">
            {items.map(item => {
              const expanded = expandedId === item.id;
              const points = pointState[item.id];
              return (
                <Card className={`knowledge-item${expanded ? ' expanded' : ''}`} key={item.id}>
                  <div className="knowledge-item-main">
                    <span className="knowledge-file-icon"><FileText size={21} /></span>
                    <div className="knowledge-file-copy">
                      <h3 title={item.name}>{item.name}</h3>
                      <div className="knowledge-meta">
                        <span>{formatChars(item.charCount)}</span>
                        <span>{item.sourceType === 'UPLOAD' ? '文件上传' : '本地导入'}</span>
                        <span>{formatDate(item.createdAt)}</span>
                      </div>
                    </div>
                    <div className="knowledge-item-actions">
                      <button type="button" className="knowledge-detail-button" onClick={() => void togglePoints(item.id)}>
                        {pointLoading === item.id ? <Loader2 className="spin" size={15} /> : <Sparkles size={15} />}
                        知识点
                        <ChevronDown className={expanded ? 'up' : ''} size={15} />
                      </button>
                      <button
                        type="button"
                        className="knowledge-delete-button"
                        onClick={() => void remove(item)}
                        title="删除资料"
                        aria-label={`删除 ${item.name}`}
                      >
                        <Trash2 size={16} />
                      </button>
                    </div>
                  </div>

                  {expanded && (
                    <div className="knowledge-points-panel">
                      {pointLoading === item.id && !points ? (
                        <Loading label="读取提取结果…" />
                      ) : points?.indexed ? (
                        points.points.length > 0 ? (
                          <div className="knowledge-points-grid">
                            {points.points.map(point => (
                              <div className="knowledge-point" key={point.name}>
                                <div><strong>{point.name}</strong><span>{point.chunkCount} 个内容片段</span></div>
                                {point.snippets[0] && <p>{point.snippets[0]}</p>}
                              </div>
                            ))}
                          </div>
                        ) : <p className="knowledge-processing">资料已完成索引，暂未提取出明确知识点。</p>
                      ) : (
                        <div className="knowledge-processing">
                          <span>资料仍在分析中，稍后即可查看知识点。</span>
                          <button type="button" onClick={() => void refreshPoints(item.id)}>重新检查</button>
                        </div>
                      )}
                    </div>
                  )}
                </Card>
              );
            })}
          </div>
        )}
      </section>
    </div>
  );
}
