import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Send, Check, Upload, X } from 'lucide-react';
import { Button, Card, Tag } from '../components/ui';
import { studyPlan, corpus, type TutorStream } from '../api/drill';
import { ApiError } from '../api/client';
import type { PlanChatMessage, StudyPlanDraft, ConceptValidationResponse } from '../api/types';

/** 新建学习方向：无状态多轮对话，LLM 收敛出 draft 后确认落库。 */
export function IntakeChat() {
  const navigate = useNavigate();
  const [messages, setMessages] = useState<PlanChatMessage[]>([]);
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  const [draft, setDraft] = useState<StudyPlanDraft | null>(null);
  const [validation, setValidation] = useState<ConceptValidationResponse | null>(null);
  const [validating, setValidating] = useState(false);

  // 流式回复流引用：卸载时取消
  const streamRef = useRef<TutorStream | null>(null);
  useEffect(() => () => streamRef.current?.cancel(), []);

  // 可选：先上传自己的书 / 项目资料，AI 基于它规划
  const [corpusId, setCorpusId] = useState<number | null>(null);
  const [corpusName, setCorpusName] = useState('');
  const [uploading, setUploading] = useState(false);

  // C1：从资料中识别到的候选知识点（异步索引，轮询直到就绪）
  const [kp, setKp] = useState<{ name: string; chunkCount: number; snippets: string[] }[] | null>(null);
  const [kpIndexed, setKpIndexed] = useState(false);
  const [kpChecking, setKpChecking] = useState(false);
  const [selected, setSelected] = useState<Set<string>>(new Set());

  // 上传成功后轮询候选知识点（索引是异步的，最多等 ~30s）
  const pollKnowledgePoints = async (id: number) => {
    setKpChecking(true);
    try {
      for (let i = 0; i < 10; i++) {
        const res = await corpus.knowledgePoints(id);
        if (res.indexed) {
          setKp(res.points);
          setKpIndexed(true);
          setSelected(new Set(res.points.map((p) => p.name)));
          return;
        }
        await new Promise((r) => setTimeout(r, 3000));
      }
    } catch {
      // 轮询失败不致命：用户仍可继续对话规划
    } finally {
      setKpChecking(false);
    }
  };

  const onFile = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    e.target.value = ''; // 允许重复选同一文件
    if (!file) return;
    setUploading(true);
    setErr('');
    try {
      const res = await corpus.upload(file);
      setCorpusId(res.id);
      setCorpusName(res.name);
      setKp(null);
      setKpIndexed(false);
      void pollKnowledgePoints(res.id);
    } catch (e2) {
      setErr(e2 instanceof ApiError ? e2.message : '上传失败');
    } finally {
      setUploading(false);
    }
  };

  const detachCorpus = () => {
    setCorpusId(null);
    setCorpusName('');
    setKp(null);
    setKpIndexed(false);
  };

  // 桌面端（Electron）下可免上传：本地模式直接把路径交给后端读盘；
  // 云模式（后端在服务器）下服务器读不到本机路径，改由 Electron 自己读字节传上来。
  const hasElectron = typeof window !== 'undefined' && !!window.electronAPI?.pickFolder;
  const [isCloud, setIsCloud] = useState<boolean | null>(null);
  useEffect(() => {
    if (hasElectron) {
      window.electronAPI?.isCloud().then(setIsCloud).catch(() => setIsCloud(false));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const ingestPath = async (path: string) => {
    setUploading(true);
    setErr('');
    try {
      if (isCloud && window.electronAPI?.collectPath) {
        // 云模式：本机读盘 → 字节传给服务器解析
        const local = await window.electronAPI.collectPath(path);
        if ('error' in local) throw new Error(local.error);
        const form = new FormData();
        form.append('folderName', local.name);
        for (const f of local.files) {
          const bin = atob(f.data);
          const bytes = new Uint8Array(bin.length);
          for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
          form.append('files', new Blob([bytes]), f.name);
        }
        const res = await corpus.fromFiles(form);
        setCorpusId(res.id);
        setCorpusName(res.name);
        setKp(null);
        setKpIndexed(false);
        void pollKnowledgePoints(res.id);
      } else {
        // 本地模式：后端直接读盘，免上传
        const res = await corpus.fromPath(path);
        setCorpusId(res.id);
        setCorpusName(res.name);
        setKp(null);
        setKpIndexed(false);
        void pollKnowledgePoints(res.id);
      }
    } catch (e2) {
      setErr(e2 instanceof ApiError ? e2.message : e2 instanceof Error ? e2.message : '读取本地文件失败');
    } finally {
      setUploading(false);
    }
  };

  const pickFile = async () => {
    const p = await window.electronAPI?.pickFile();
    if (p) await ingestPath(p);
  };

  const pickFolder = async () => {
    const p = await window.electronAPI?.pickFolder();
    if (p) await ingestPath(p);
  };

  const validateDraft = async (nextDraft: StudyPlanDraft | null) => {
    setDraft(nextDraft);
    setValidation(null);
    if (!nextDraft || nextDraft.points.length === 0) return;
    setValidating(true);
    try {
      setValidation(await studyPlan.validateCandidates(nextDraft));
    } catch {
      // 核验失败不阻断用户编辑和确认，前端显示为未完成核验
    } finally {
      setValidating(false);
    }
  };
  const send = () => {
    const text = input.trim();
    if (!text || busy) return;
    const next: PlanChatMessage[] = [...messages, { role: 'user', content: text }];
    setMessages([...next, { role: 'assistant', content: '' }]); // 流式占位气泡
    setInput('');
    setBusy(true);
    setErr('');
    setDraft(null);

    let reply = '';
    streamRef.current = studyPlan.intakeStream(
      next,
      corpusId ?? undefined,
      (tok) => {
        reply += tok;
        // 更新最后一个（占位）气泡为已累积的回复
        setMessages((prev) => [...prev.slice(0, -1), { role: 'assistant', content: reply }]);
      },
      (draft) => { void validateDraft(draft); },
      () => {
        streamRef.current = null;
        setBusy(false);
      },
      (_status?: number, message?: string) => {
        setMessages((prev) => prev.slice(0, -1)); // 移除失败的气泡
        streamRef.current = null;
        setErr(message || '对话失败');
        setBusy(false);
      },
    );
  };

  const confirm = async () => {
    if (!draft) return;
    setBusy(true);
    setErr('');
    try {
      // 把资料一并交给后端，confirm 会把它绑定到这个新方向
      await studyPlan.confirm({ ...draft, corpusId: corpusId ?? undefined });
      navigate('/');
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : '创建失败');
    } finally {
      setBusy(false);
    }
  };

  const keepTalking = () => {
    setDraft(null);
    setValidation(null);
    setMessages([
      ...messages,
      { role: 'assistant', content: '好，那我们继续聊，你还想补充什么？' },
    ]);
  };

  // C1：把勾选的资料候选知识点并入 AI 规划（按名去重；layer 默认 1，note 用块摘要）
  const mergeSelected = () => {
    if (!draft || !kp) return;
    const extra = (kp || [])
      .filter((p) => selected.has(p.name))
      .filter((p) => !draft.points.some((d) => d.name.trim() === p.name.trim()))
      .map((p) => ({
        name: p.name,
        layer: 1,
        note: p.snippets[0] ?? '',
      }));
    if (extra.length === 0) return;
    setDraft({ ...draft, points: [...draft.points, ...extra] });
  };

  const toggleSelected = (name: string) => {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(name)) next.delete(name);
      else next.add(name);
      return next;
    });
  };

  return (
    <div className="page">
      <header className="page-head">
        <span className="eyebrow">练习 · 新建方向</span>
        <h1>聊聊你想学什么</h1>
        <p>告诉我方向、你的基础和目标，我会帮你拆成一份层级学习规划。</p>
      </header>

      <Card className="upload-card">
        <span className="eyebrow">基于自己的资料学习（可选）</span>
        <p className="upload-hint">
          先上传你的书 / 项目文档（PDF、txt、md、docx，带文字层），AI 会基于它的真实内容帮你规划。
        </p>
        {corpusId == null ? (
          <>
            <label className="upload-btn">
              <Upload size={15} strokeWidth={1.8} />
              {uploading ? '解析中…' : '上传资料'}
              <input type="file" accept=".pdf,.txt,.md,.docx" hidden onChange={onFile} />
            </label>
            {hasElectron && (
              <div className="path-actions">
                <button type="button" className="upload-btn" onClick={pickFile} disabled={uploading || isCloud === null}>
                  选择本地文件
                </button>
                <button type="button" className="upload-btn" onClick={pickFolder} disabled={uploading || isCloud === null}>
                  选择本地文件夹
                </button>
              </div>
            )}
            {hasElectron && (
              <p className="upload-hint">
                {isCloud
                  ? '云模式下由桌面端在你本机读盘，解析后上传服务器（大文件夹请控制在 40MB 以内）。'
                  : '大项目直接选文件夹，后端在你本机读盘解析，免上传。'}
              </p>
            )}
          </>
        ) : (
          <div className="attached-file">
            <span className="file-name">{corpusName}</span>
            <span className="eyebrow">已附加，规划将基于它</span>
            <button className="file-remove" onClick={detachCorpus} title="移除">
              <X size={14} strokeWidth={1.8} />
            </button>
          </div>
        )}

        {/* C1：资料候选知识点（异步提取，就绪后展示，可勾选并入规划） */}
        {corpusId != null && (
          <div className="kp-panel">
            {kpChecking && <p className="upload-hint">资料分析中，正在提取知识点…（约几秒）</p>}
            {kpIndexed && kp && kp.length > 0 && (
              <>
                <span className="eyebrow">从资料中识别到的知识点（可勾选后并入规划）</span>
                <div className="kp-list">
                  {kp.map((p) => (
                    <label className="kp-item" key={p.name}>
                      <input
                        type="checkbox"
                        checked={selected.has(p.name)}
                        onChange={() => toggleSelected(p.name)}
                      />
                      <span className="kp-name">{p.name}</span>
                      {p.chunkCount > 1 && <Tag>×{p.chunkCount}</Tag>}
                      {p.snippets[0] && <span className="kp-snippet">{p.snippets[0]}</span>}
                    </label>
                  ))}
                </div>
                {draft && (
                  <button
                    type="button"
                    className="upload-btn kp-merge"
                    onClick={mergeSelected}
                    disabled={selected.size === 0}
                  >
                    并入规划（{selected.size}）
                  </button>
                )}
              </>
            )}
          </div>
        )}
      </Card>

      <Card className="chat-card">
        <div className="chat-log">
          {messages.length === 0 && (
            <div className="chat-hint">例如：「我想学前端，会用 React 但原理不熟」</div>
          )}
          {messages.map((m, i) => (
            <div className={`bubble ${m.role}`} key={i}>
              {m.role === 'assistant' && !m.content ? '思考中…' : m.content}
            </div>
          ))}
        </div>

        {draft && (
          <div className="draft-card">
            <span className="eyebrow">我拟的规划</span>
            <h3>{draft.title ?? '未命名方向'}</h3>
            {draft.goal && <p className="draft-goal">{draft.goal}</p>}
            <ul className="draft-points">
              {draft.points.map((p, i) => {
                const check = validation?.points.find((v) => v.name.trim().toLowerCase() === p.name.trim().toLowerCase());
                return (
                <li key={i}>
                  <Tag>L{p.layer}</Tag> {p.name}
                  {check?.status === 'FOUND' && <span className="validation-ok">✓ 已检索</span>}
                  {check?.status === 'NOT_FOUND' && <span className="validation-warn">⚠ 未找到</span>}
                  {check?.status === 'FAILED' && <span className="validation-warn">⚠ 检索失败</span>}
                  {p.note ? ` · ${p.note}` : ''}
                  {check?.evidence && <small className="validation-evidence">{check.evidence}</small>}
                </li>
                );
              })}
            </ul>
            {validating && <p className="upload-hint">正在逐个搜索知识点，搜索结果仅供核对，未找到不代表一定是错误……</p>}
            {!validating && validation && validation.points.some((v) => v.status !== 'FOUND') && (
              <p className="upload-hint">部分知识点暂未找到公开结果，可能是内部术语或名称需要调整，请确认后再创建。</p>
            )}
            <div className="draft-actions">
              <Button onClick={confirm} disabled={busy}>
                <Check size={16} strokeWidth={1.6} /> 确认，开始学
              </Button>
              <Button variant="ghost" onClick={keepTalking} disabled={busy}>
                继续聊
              </Button>
            </div>
          </div>
        )}
      </Card>

      {err && <div className="banner info">{err}</div>}

      <div className="chat-input">
        <textarea
          className="chat-input-textarea"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          placeholder="说点什么…（Enter 发送，Shift+Enter 换行）"
          rows={2}
          onKeyDown={(e) => {
            // 输入法组字（composition）期间按回车只是确认候选词，不能当成发送
            // isComposing 覆盖主流浏览器；keyCode===229 兜底旧版 IME
            if (e.nativeEvent.isComposing || (e.keyCode as number) === 229) return;
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault();
              send();
            }
          }}
        />
        <Button onClick={send} disabled={busy}>
          <Send size={16} strokeWidth={1.6} /> 发送
        </Button>
      </div>

      <button className="dash-link" onClick={() => navigate('/')}>
        返回首页
      </button>
    </div>
  );
}
