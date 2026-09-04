import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  ArrowRight,
  CheckCircle2,
  FileText,
  Loader2,
  Sparkles,
  Trash2,
  Upload,
  X,
} from 'lucide-react';
import { ApiError } from '../api/client';
import { resumeApi, type ResumeDetail, type ResumeListItem } from '../api/interview';
import { Badge, Button, Card, Loading } from '../components/ui';
import './ResumeManagement.css';

function errorMessage(error: unknown): string {
  if (error instanceof ApiError || error instanceof Error) return error.message;
  return '操作失败，请稍后重试';
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

function formatDate(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value));
}

function statusLabel(status: string): string {
  const labels: Record<string, string> = {
    UPLOADED: '已上传',
    ANALYZING: '分析中',
    COMPLETED: '分析完成',
    FAILED: '分析失败',
  };
  return labels[status] ?? status;
}

function statusTone(status: string): 'good' | 'warn' | 'bad' | 'soft' {
  if (status === 'COMPLETED') return 'good';
  if (status === 'FAILED') return 'bad';
  if (status === 'ANALYZING') return 'warn';
  return 'soft';
}

export function ResumeManagement() {
  const navigate = useNavigate();
  const fileRef = useRef<HTMLInputElement>(null);
  const [list, setList] = useState<ResumeListItem[] | null>(null);
  const [selected, setSelected] = useState<ResumeDetail | null>(null);
  const [loadingDetailId, setLoadingDetailId] = useState<number | null>(null);
  const [uploading, setUploading] = useState(false);
  const [deletingId, setDeletingId] = useState<number | null>(null);
  const [error, setError] = useState('');

  const loadList = () => resumeApi.list().then(setList).catch(e => setError(errorMessage(e)));

  useEffect(() => {
    loadList();
  }, []);

  const upload = async (file: File) => {
    setUploading(true);
    setError('');
    try {
      const detail = await resumeApi.upload(file);
      setSelected(detail);
      await loadList();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setUploading(false);
    }
  };

  const openDetail = async (id: number) => {
    setLoadingDetailId(id);
    setError('');
    try {
      setSelected(await resumeApi.detail(id));
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setLoadingDetailId(null);
    }
  };

  const remove = async (item: ResumeListItem) => {
    if (!window.confirm(`确认删除简历“${item.originalName}”吗？删除后不可恢复。`)) return;
    setDeletingId(item.id);
    setError('');
    try {
      await resumeApi.remove(item.id);
      setList(current => (current ?? []).filter(resume => resume.id !== item.id));
      if (selected?.id === item.id) setSelected(null);
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setDeletingId(null);
    }
  };

  return (
    <div className="page resume-page">
      <header className="resume-head">
        <div>
          <span className="eyebrow">面试准备 · RESUME</span>
          <h1>简历管理</h1>
          <p>集中管理你的简历和 AI 分析结果，模拟面试可直接使用这里的简历。</p>
        </div>
        <div className="resume-head-actions">
          <Button variant="ghost" onClick={() => navigate('/rehearsal')}>
            <Sparkles size={16} strokeWidth={1.8} /> 模拟面试
          </Button>
          <Button onClick={() => fileRef.current?.click()} disabled={uploading}>
            {uploading
              ? <Loader2 className="spin" size={16} strokeWidth={1.8} />
              : <Upload size={16} strokeWidth={1.8} />}
            {uploading ? '上传并分析中…' : '上传简历'}
          </Button>
          <input
            ref={fileRef}
            type="file"
            accept=".pdf,.docx,.txt"
            hidden
            onChange={(event) => {
              const file = event.target.files?.[0];
              event.target.value = '';
              if (file) void upload(file);
            }}
          />
        </div>
      </header>

      {error && <div className="banner">{error}</div>}

      {list === null ? (
        <Loading label="读取简历列表…" />
      ) : list.length === 0 ? (
        <div className="resume-empty">
          <span className="resume-empty-icon"><FileText size={28} strokeWidth={1.5} /></span>
          <h3>还没有简历</h3>
          <p>上传 PDF、DOCX 或 TXT 文件，AI 会自动解析并给出改进建议。</p>
          <Button onClick={() => fileRef.current?.click()} disabled={uploading}>
            <Upload size={16} strokeWidth={1.8} /> 上传第一份简历
          </Button>
        </div>
      ) : (
        <div className="resume-grid">
          {list.map(item => (
            <Card
              key={item.id}
              className={`resume-card${selected?.id === item.id ? ' active' : ''}`}
            >
              <button
                type="button"
                className="resume-card-main"
                onClick={() => void openDetail(item.id)}
                aria-label={`查看 ${item.originalName} 的分析`}
              >
                <span className="resume-file-icon"><FileText size={22} strokeWidth={1.6} /></span>
                <span className="resume-card-copy">
                  <strong>{item.originalName}</strong>
                  <small>{formatSize(item.fileSize)} · {formatDate(item.createdAt)}</small>
                </span>
                {loadingDetailId === item.id
                  ? <Loader2 className="spin resume-open-icon" size={17} />
                  : <ArrowRight className="resume-open-icon" size={17} strokeWidth={1.8} />}
              </button>
              <div className="resume-card-foot">
                <Badge kind={statusTone(item.status)}>{statusLabel(item.status)}</Badge>
                {item.overallScore != null && <span className="resume-score"><strong>{item.overallScore}</strong>/100</span>}
                <button
                  type="button"
                  className="resume-delete"
                  onClick={() => void remove(item)}
                  disabled={deletingId === item.id}
                  title="删除简历"
                  aria-label={`删除 ${item.originalName}`}
                >
                  {deletingId === item.id ? <Loader2 className="spin" size={15} /> : <Trash2 size={15} strokeWidth={1.7} />}
                </button>
              </div>
            </Card>
          ))}
        </div>
      )}

      {selected && (
        <Card className="resume-detail">
          <div className="resume-detail-head">
            <div>
              <span className="eyebrow">AI ANALYSIS</span>
              <h2>{selected.originalName}</h2>
            </div>
            <div className="resume-detail-actions">
              {selected.overallScore != null && (
                <span className="resume-detail-score"><strong>{selected.overallScore}</strong><small>/100</small></span>
              )}
              <button type="button" className="resume-detail-close" onClick={() => setSelected(null)} aria-label="关闭详情">
                <X size={18} strokeWidth={1.8} />
              </button>
            </div>
          </div>

          {selected.errorMessage && <div className="banner">{selected.errorMessage}</div>}
          {selected.summary && <p className="resume-summary">{selected.summary}</p>}

          <div className="resume-analysis-grid">
            <section>
              <h3 className="good"><CheckCircle2 size={17} /> 优势亮点</h3>
              {(selected.strengths ?? []).length > 0
                ? <ul>{selected.strengths.map((text, index) => <li key={index}>{text}</li>)}</ul>
                : <p>暂无分析结果</p>}
            </section>
            <section>
              <h3 className="warn"><Sparkles size={17} /> 改进建议</h3>
              {(selected.suggestions ?? []).length > 0
                ? <ul>{selected.suggestions.map((text, index) => <li key={index}>{text}</li>)}</ul>
                : <p>暂无建议</p>}
            </section>
          </div>

          {(selected.weaknesses ?? []).length > 0 && (
            <section className="resume-weaknesses">
              <h3>需要关注</h3>
              <ul>{selected.weaknesses.map((text, index) => <li key={index}>{text}</li>)}</ul>
            </section>
          )}
        </Card>
      )}
    </div>
  );
}
