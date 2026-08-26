import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import { FolderOpen, UploadCloud, Loader2, BookOpen, FileCode2 } from 'lucide-react';
import { projectApi, type ProjectStatus, type DomainView } from '../api/project';
import { Button, Card, Badge, Loading } from '../components/ui';
import { ApiError } from '../api/client';
import { useNavigate } from 'react-router-dom';
import './ProjectPage.css';

function msg(e: unknown): string {
  if (e instanceof ApiError) return e.message;
  if (e instanceof Error && e.message) return e.message;
  return '操作失败，请重试';
}

/** 是否桌面端（提供本地路径导入能力）。 */
const isDesktop = typeof window !== 'undefined' && !!window.electronAPI?.getLlmKey;

/**
 * 项目导入 → 自动分析业务域 → 生成学习计划。
 * 用户上传 zip（或桌面端指定本地路径），后台多 Agent 分析，
 * 页面轮询展示进度，最终可一键「创建学习计划」。
 */
export function ProjectPage() {
  const navigate = useNavigate();
  const [projects, setProjects] = useState<ProjectStatus[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  const [path, setPath] = useState('');
  const [creatingPlan, setCreatingPlan] = useState<number | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const projectsRef = useRef<ProjectStatus[]>([]);
  projectsRef.current = projects;

  const refresh = useCallback(async (silent = false) => {
    if (!silent) setLoading(true);
    try {
      const list = await projectApi.list();
      setProjects(list);
      setErr('');
    } catch (e) {
      if (!silent) setErr(msg(e));
    } finally {
      setLoading(false);
    }
  }, []);

  // 轮询进行中的项目：任何项目的状态是 PENDING / ANALYZING 就继续轮询
  useEffect(() => {
    void refresh();
    pollRef.current = setInterval(() => {
      const hasRunning = projectsRef.current.some(
        (p) => p.status === 'PENDING' || p.status === 'ANALYZING'
      );
      if (hasRunning) {
        void refresh(true);
      }
    }, 3000);
    return () => { if (pollRef.current) clearInterval(pollRef.current); };
  }, [refresh]);

  const uploadZip = async (file: File) => {
    setBusy(true); setErr('');
    try {
      await projectApi.importZip(file);
      await refresh();
    } catch (e) { setErr(msg(e)); }
    finally { setBusy(false); if (fileRef.current) fileRef.current.value = ''; }
  };

  const importLocalPath = async () => {
    if (!path.trim()) { setErr('请输入项目路径'); return; }
    setBusy(true); setErr('');
    try {
      await projectApi.importPath(path.trim());
      await refresh();
      setPath('');
    } catch (e) { setErr(msg(e)); }
    finally { setBusy(false); }
  };

  const createPlan = async (p: ProjectStatus) => {
    setCreatingPlan(p.id); setErr('');
    try {
      await projectApi.createPlan(p.id);
      navigate('/plan');
    } catch (e) { setErr(msg(e)); }
    finally { setCreatingPlan(null); }
  };

  const statusBadge = (s: string) => {
    if (s === 'READY') return <Badge kind="good">分析完成</Badge>;
    if (s === 'FAILED') return <Badge kind="bad">分析失败</Badge>;
    if (s === 'ANALYZING') return <Badge kind="warn">分析中…</Badge>;
    return <Badge kind="soft">排队中</Badge>;
  };

  return (
    <div className="page project-page">
      <div className="page-head">
        <h1>项目学习</h1>
        <p>导入任意项目源码，AI 自动分析业务模块，生成专属学习计划</p>
      </div>

      {/* 导入区 */}
      <Card className="project-import-card">
        <div className="project-import-row">
          <input
            ref={fileRef}
            type="file"
            accept=".zip"
            hidden
            onChange={(e) => { if (e.target.files?.[0]) void uploadZip(e.target.files[0]); }}
          />
          <Button variant="ghost" onClick={() => fileRef.current?.click()} disabled={busy}>
            <UploadCloud size={16} /> <span>上传项目 zip</span>
          </Button>
          {isDesktop && (
            <div className="project-path-row">
              <input
                className="project-path-input"
                value={path}
                placeholder="或输入本地项目路径，如 /Users/me/project"
                onChange={(e) => setPath(e.target.value)}
              />
              <Button variant="ghost" onClick={importLocalPath} disabled={busy || !path.trim()}>
                <FolderOpen size={16} /> 导入
              </Button>
            </div>
          )}
        </div>
        {err && <div className="project-err">{err}</div>}
      </Card>

      {/* 项目列表 */}
      {loading ? <Loading label="加载项目…" /> : projects.length === 0 ? (
        <Card className="project-empty">
          <FileCode2 size={40} strokeWidth={1.2} />
          <p>还没有导入的项目。上传一个源码 zip，或输入本地路径开始。</p>
        </Card>
      ) : (
        <div className="project-list">
          {projects.map((p) => (
            <Card key={p.id} className="project-card">
              <div className="project-card-head">
                <div className="project-card-title">
                  <span className="project-name">{p.name}</span>
                  {statusBadge(p.status)}
                </div>
                <div className="project-actions">
                  {p.status === 'READY' && (
                    <Button variant="primary" onClick={() => createPlan(p)} disabled={creatingPlan === p.id}>
                      {creatingPlan === p.id ? <Loader2 className="spin" size={15} /> : <BookOpen size={15} />}
                      {creatingPlan === p.id ? '创建中…' : '创建学习计划'}
                    </Button>
                  )}
                  {p.status === 'FAILED' && <Button variant="quiet" onClick={() => refresh()}>重试</Button>}
                  {(p.status === 'PENDING' || p.status === 'ANALYZING') && (
                    <span className="project-anim">
                      <Loader2 className="spin" size={15} /> 分析中
                    </span>
                  )}
                </div>
              </div>

              {p.techStack && (
                <div className="project-tech">
                  {JSON.parse(p.techStack || '[]').map((t: string) => (
                    <Tag key={t}>{t}</Tag>
                  ))}
                </div>
              )}

              {p.errorMsg && <div className="project-err">{p.errorMsg}</div>}

              {p.domains.length > 0 && (
                <div className="project-domains">
                  {p.domains.map((d) => (
                    <DomainBlock key={d.id} domain={d} />
                  ))}
                </div>
              )}
            </Card>
          ))}
        </div>
      )}
    </div>
  );
}

function DomainBlock({ domain }: { domain: DomainView }) {
  const [open, setOpen] = useState(false);
  return (
    <div className="project-domain">
      <button className="project-domain-head" onClick={() => setOpen((v) => !v)}>
        <span className="project-domain-name">{domain.name}</span>
        <span className="project-domain-count">{domain.subPoints.length} 个子点</span>
      </button>
      {domain.overview && <div className="project-domain-overview">{domain.overview}</div>}
      {open && (
        <div className="project-subpoints">
          {domain.subPoints.map((s) => (
            <div key={s.id} className="project-subpoint">
              <div className="project-subpoint-name">{s.name}</div>
              {s.description && <div className="project-subpoint-desc">{s.description}</div>}
              {s.refFiles.length > 0 && (
                <div className="project-subpoint-files">
                  {s.refFiles.map((f) => <span key={f} className="project-file-tag">{f}</span>)}
                </div>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function Tag({ children }: { children: ReactNode }) {
  return <span className="project-tech-tag">{children}</span>;
}