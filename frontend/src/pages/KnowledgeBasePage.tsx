import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, ArrowUpRight, BookOpen, Check, Database, ExternalLink, FileText, FolderUp, Layers, Loader2, MessagesSquare, RefreshCw, Search, Sparkles, Trash2, Wrench } from 'lucide-react';
import { corpus } from '../api/drill';
import { libraryApi, libraryError, LIBRARY_ACCEPT, INDEX_LABELS } from '../api/library';
import type { CorpusView, CorpusDetail } from '../api/types';
import { Button, Loading } from '../components/ui';
import { useFileDrop } from '../lib/useFileDrop';
import './KnowledgeBasePage.css';

const chars = (n: number) => n < 1000 ? `${n} 字符` : `${(n / 1000).toFixed(1)}k 字符`;
const date = (value?: string | null) => value ? new Date(value).toLocaleDateString('zh-CN') : '刚刚导入';
const pending = (item: CorpusView) => ['PENDING', 'RUNNING'].includes(item.indexState ?? 'PENDING');

export function KnowledgeBasePage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const [items, setItems] = useState<CorpusView[]>([]);
  const [detail, setDetail] = useState<CorpusDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [uploading, setUploading] = useState('');
  const [action, setAction] = useState('');
  const [query, setQuery] = useState('');
  const [tag, setTag] = useState('全部');
  const fileRef = useRef<HTMLInputElement>(null);
  const pageRef = useRef<HTMLDivElement>(null);
  const routeVersion = useRef(0);
  useEffect(() => { routeVersion.current++; return () => { routeVersion.current++; }; }, [id]);
  useLayoutEffect(() => { pageRef.current?.closest('.main')?.scrollTo(0, 0); }, [id]);
  const uploadBusy = useRef(false);
  const epoch = useRef(0);
  const load = useCallback(async (quiet = false) => {
    const version = ++epoch.current;
    if (!quiet) setLoading(true);
    setError('');
    try {
      if (id) {
        const value = await libraryApi.detail(Number(id));
        if (version === epoch.current) setDetail(value);
      } else {
        const value = await corpus.list();
        if (version === epoch.current) setItems(value);
      }
    } catch (e) { if (version === epoch.current) setError(libraryError(e)); }
    finally { if (version === epoch.current) setLoading(false); }
  }, [id]);
  useEffect(() => { setDetail(null); setNotice(''); void load(); return () => { epoch.current++; }; }, [load]);
  const processing = id ? !!detail && pending(detail.document) : items.some(pending);
  useEffect(() => {
    if (!processing) return;
    const timer = window.setInterval(() => { if (!document.hidden) void load(true); }, 6000);
    return () => window.clearInterval(timer);
  }, [processing, load]);
  const tags = useMemo(() => [...new Set(items.flatMap(item => item.topics ?? []))].slice(0, 14), [items]);
  const filtered = items.filter(item => (tag === '全部' || item.topics?.includes(tag))
    && `${item.name} ${item.overview ?? ''} ${(item.topics ?? []).join(' ')}`.toLowerCase().includes(query.trim().toLowerCase()));

  const upload = async (files: File[]) => {
    if (uploadBusy.current || !files.length) return;
    if (files.length > 10) { setError('每次最多导入 10 份资料'); return; }
    for (const file of files) {
      if (!LIBRARY_ACCEPT.split(',').includes('.' + file.name.split('.').pop()?.toLowerCase())) { setError('支持 PDF、Word（DOCX）、TXT、Markdown 格式'); return; }
      if (!file.size || file.size > 20 * 1024 * 1024) { setError('资料不能为空，每份文件最大 20 MB'); return; }
    }
    uploadBusy.current = true; setError(''); setNotice('');
    const originVersion = routeVersion.current;
    let count = 0;
    try {
      for (const file of files) {
        setUploading(`正在导入 ${count + 1}/${files.length} · ${file.name}`);
        await corpus.upload(file); count++;
      }
      if (originVersion === routeVersion.current) setNotice(`已导入 ${count} 份资料，知识点索引将在后台整理。`);
    } catch (e) { if (originVersion === routeVersion.current) setError(`${count ? `已成功导入 ${count} 份。` : ''}${libraryError(e)}`); }
    finally {
      uploadBusy.current = false; setUploading('');
      if (originVersion === routeVersion.current) {
        if (!id) corpus.list().then(data => { if (originVersion === routeVersion.current) setItems(data); }).catch(e => setError(libraryError(e)));
        else navigate('/knowledge-base');
      }
    }
  };
  const { dragging, dropProps } = useFileDrop(files => { void upload(files); }, !!uploading);
  const openOriginal = async (document: CorpusView) => {
    setAction('original'); setError('');
    try { await libraryApi.openOriginal(document.id); } catch (e) { setError(libraryError(e)); }
    finally { setAction(''); }
  };
  const remove = async (document: CorpusView) => {
    if (!window.confirm(`删除「${document.name}」？被学习计划或面试记录引用的资料会受到保护。`)) return;
    setAction('delete'); setError('');
    try { await corpus.remove(document.id); navigate('/knowledge-base'); if (!id) setItems(v => v.filter(x => x.id !== document.id)); }
    catch (e) { setError(libraryError(e)); } finally { setAction(''); }
  };
  const reindex = async () => {
    if (!detail) return;
    setAction('index'); setError('');
    try {
      await libraryApi.reindex(detail.document.id);
      setDetail(value => value && ({ ...value, document: { ...value.document, indexState: 'RUNNING' } }));
      setNotice('已提交重新整理；若未配置模型，将保留基础索引。');
    } catch (e) { setError(libraryError(e)); } finally { setAction(''); }
  };

  return <div ref={pageRef} className="page knowledge-page" {...dropProps}>
    {dragging && <div className="kb-drop-overlay"><FolderUp size={36} /><h2>让新知识，有处安放</h2><p>松开鼠标导入资料 · 每份最大 20 MB</p></div>}
    <input ref={fileRef} type="file" accept={LIBRARY_ACCEPT} multiple hidden onChange={e => { const files = Array.from(e.target.files ?? []); e.target.value = ''; void upload(files); }} />
    <header className="kb-header">
      <div><span className="eyebrow">KNOWLEDGE LIBRARY · {id ? '资料详情' : '知识库管理'}</span>
        <h1>{id ? detail?.document.name ?? '资料详情' : '让积累，成为你的底气。'}</h1>
        <p>{id ? '读懂资料的结构，看见它如何参与学习与面试。' : '整理自己的资料，让每一次规划与练习，都有据可循。'}</p></div>
      {id ? <Link className="kb-text-link" to="/knowledge-base"><ArrowLeft size={15} />返回知识库</Link>
        : <div className="kb-header-actions"><Link className="kb-secondary-link" to="/knowledge-base/tools"><Wrench size={16} />工具库</Link>
          <Button disabled={!!uploading} onClick={() => fileRef.current?.click()}><FolderUp size={16} />导入资料</Button></div>}
    </header>
    {error && <div className="banner warn" role="alert">{error}<button className="kb-text-link" onClick={() => void load()}>重新读取</button></div>}
    {notice && <p className="kb-notice" role="status"><Check size={15} />{notice}</p>}
    {uploading && <p className="kb-notice" role="status"><Loader2 size={15} className="spin" />{uploading}</p>}
    {loading ? <Loading label="读取资料与索引…" /> : id ? detail && <>
      <div className="kb-detail-hero">
        <span className="kb-document-icon"><FileText size={30} strokeWidth={1.5} /></span>
        <div><span className={`kb-status ${detail.document.indexState?.toLowerCase()}`}>{INDEX_LABELS[detail.document.indexState ?? 'PENDING']}</span>
          <p>{chars(detail.document.charCount)} · {detail.sections.length} 个内容片段 · {date(detail.document.createdAt)}</p></div>
        <Button variant="ghost" disabled={!!action} onClick={() => void openOriginal(detail.document)}><ExternalLink size={16} />查看原文</Button>
      </div>
      {!detail.document.hasOriginal && <p className="kb-muted">历史资料只保存了解析文本；“查看原文”会在浏览器打开完整文本。</p>}
      <div className="kb-detail-grid"><div className="kb-detail-main">
        <section className="kb-panel"><span className="kb-section-label">01 / OVERVIEW</span><h2>内容简介</h2><p className="kb-overview">{detail.document.overview}</p>
          <div className="kb-tags">{detail.document.topics?.map(t => <span key={t}>{t}</span>)}</div></section>
        <section className="kb-panel"><div className="kb-section-top"><div><span className="kb-section-label">02 / INDEX</span><h2>知识点与内容索引</h2></div>
          <button className="kb-text-link" disabled={!!action} onClick={() => void reindex()}><RefreshCw size={14} />重新整理</button></div>
          {detail.document.indexState === 'BASIC' && <p className="kb-muted">当前为章节基础索引。配置模型后可重新整理，补充 AI 标签与摘要。</p>}
          {!detail.sections.length && <p className="kb-muted">{pending(detail.document) ? '正在整理索引，请稍候。' : '尚无索引，可点击重新整理。'}原文仍可查看。</p>}
          <div className="kb-index-list">{detail.sections.map(section => <Link key={section.id} to={`/knowledge-base/tools?corpus=${id}&section=${section.id}`} className="kb-index-row">
            <span className="kb-index-number">{String(section.sequence + 1).padStart(2, '0')}</span><div><h3>{section.topic || section.title}</h3><p>{section.summary || section.title}</p><small>{chars(section.charCount)} · 前往工具库阅读 / 翻译</small></div><ArrowUpRight size={16} /></Link>)}</div>
        </section>
      </div><aside className="kb-detail-aside">
        <section className="kb-panel kb-use-panel"><Sparkles size={24} /><h2>把资料用起来</h2><p>从这份资料出发，让学习更聚焦。</p>
          <Link to={`/intake?corpus=${id}`}><BookOpen size={17} />生成学习计划<ArrowUpRight size={15} /></Link>
          <Link to={`/rehearsal?corpus=${id}`}><MessagesSquare size={17} />生成模拟面试<ArrowUpRight size={15} /></Link>
          <Link to={`/knowledge-base/tools?corpus=${id}`}><Wrench size={17} />打开知识库工具<ArrowUpRight size={15} /></Link></section>
        <section className="kb-panel"><span className="kb-section-label">03 / CONNECTIONS</span><h2>资料使用记录 <small>{detail.usages.length}</small></h2>
          {!detail.usages.length && <p className="kb-muted">还没有关联的计划或面试。完成创建后会自动记录在这里。</p>}
          <div className="kb-usage-list">{detail.usages.map(u => <Link key={u.kind + u.id} to={u.kind === 'PLAN' ? `/plan?planId=${u.id}` : `/rehearsal/history/${u.id}`}>
            {u.kind === 'PLAN' ? <BookOpen size={16} /> : <MessagesSquare size={16} />}<span>{u.title}<small>{u.kind === 'PLAN' ? '学习计划' : u.kind === 'INTERVIEW_PLAN' ? '通过关联计划使用知识点' : '直接基于资料出题'}</small></span><ArrowUpRight size={13} /></Link>)}</div>
        </section><button className="kb-delete" disabled={!!action} onClick={() => void remove(detail.document)}><Trash2 size={14} />删除这份资料</button>
      </aside></div>
    </> : <>
      <section className="kb-stats" aria-label="知识库统计">
        <div><Database size={19} /><span><strong>{items.length}</strong><small>份学习资料</small></span></div>
        <div><Layers size={19} /><span><strong>{items.reduce((n, item) => n + (item.chunkCount ?? 0), 0)}</strong><small>个内容索引</small></span></div>
        <div><BookOpen size={19} /><span><strong>{chars(items.reduce((n, item) => n + item.charCount, 0))}</strong><small>可复用知识内容</small></span></div>
      </section>
      <div className="kb-library-toolbar"><div><h2>我的资料</h2><p>按主题整理，按目标使用</p></div>
        <label className="kb-search"><Search size={16} /><input aria-label="搜索知识库" value={query} onChange={e => setQuery(e.target.value)} placeholder="搜索资料、标签或知识点" /></label>
        <button className="kb-icon-button" aria-label="刷新知识库" onClick={() => void load(true)}><RefreshCw size={16} /></button></div>
      <div className="kb-filters" aria-label="知识主题筛选">{['全部', ...tags].map(t => <button key={t} className={tag === t ? 'active' : ''} onClick={() => setTag(t)}>{t}</button>)}</div>
      {!filtered.length ? <div className="kb-empty"><BookOpen size={35} /><h2>{items.length ? '没有匹配的资料' : '你的下一份知识，从这里开始'}</h2>
        <p>{items.length ? '试试其他关键词或主题。' : '点击右上角“导入资料”，或把文件拖到此页面。支持 PDF、DOCX、TXT、Markdown。'}</p>
        {items.length > 0 && <button className="kb-text-link" onClick={() => { setQuery(''); setTag('全部'); }}>重置筛选</button>}</div>
        : <div className="kb-document-grid">{filtered.map((item, i) => <article className={`kb-document tone-${i % 3}`} key={item.id}>
          <Link className="kb-document-main" to={`/knowledge-base/${item.id}`}><div className="kb-document-top"><span className="kb-document-icon"><FileText size={24} strokeWidth={1.5} /></span><span className={`kb-status ${item.indexState?.toLowerCase()}`}>{INDEX_LABELS[item.indexState ?? 'PENDING']}</span></div>
            <h3>{item.name}</h3><p>{item.overview || '正在整理内容简介，原文已保存。'}</p><div className="kb-tags">{(item.topics ?? []).slice(0, 4).map(t => <span key={t}>{t}</span>)}{!item.topics?.length && <span>待整理知识点</span>}</div>
            <footer><span>{date(item.createdAt)} · {chars(item.charCount)}</span><ArrowUpRight size={17} /></footer></Link>
        </article>)}</div>}
      <p className="kb-bottom-note">单份最大 20 MB / 20 万字符 · 资料仅对当前账号可见 · AI 标签与摘要请结合原文核对</p>
    </>}
  </div>;
}
