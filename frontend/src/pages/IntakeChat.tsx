import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Send, Check, Upload, X } from 'lucide-react';
import { Button, Card, Tag } from '../components/ui';
import { studyPlan, corpus, type TutorStream } from '../api/drill';
import { ApiError } from '../api/client';
import type { PlanChatMessage, StudyPlanDraft } from '../api/types';

/** 新建学习方向：无状态多轮对话，LLM 收敛出 draft 后确认落库。 */
export function IntakeChat() {
  const navigate = useNavigate();
  const [messages, setMessages] = useState<PlanChatMessage[]>([]);
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  const [draft, setDraft] = useState<StudyPlanDraft | null>(null);

  // 流式回复流引用：卸载时取消
  const streamRef = useRef<TutorStream | null>(null);
  useEffect(() => () => streamRef.current?.cancel(), []);

  // 可选：先上传自己的书 / 项目资料，AI 基于它规划
  const [corpusId, setCorpusId] = useState<number | null>(null);
  const [corpusName, setCorpusName] = useState('');
  const [uploading, setUploading] = useState(false);

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
    } catch (e2) {
      setErr(e2 instanceof ApiError ? e2.message : '上传失败');
    } finally {
      setUploading(false);
    }
  };

  const detachCorpus = () => {
    setCorpusId(null);
    setCorpusName('');
  };

  // 桌面端（Electron）下可免上传，直接把本地路径交给后端读盘
  const hasElectron = typeof window !== 'undefined' && !!window.electronAPI?.pickFolder;

  const ingestPath = async (path: string) => {
    setUploading(true);
    setErr('');
    try {
      const res = await corpus.fromPath(path);
      setCorpusId(res.id);
      setCorpusName(res.name);
    } catch (e2) {
      setErr(e2 instanceof ApiError ? e2.message : '读取本地文件失败');
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
      (draft) => setDraft(draft),
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
    setMessages([
      ...messages,
      { role: 'assistant', content: '好，那我们继续聊，你还想补充什么？' },
    ]);
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
                <button type="button" className="upload-btn" onClick={pickFile} disabled={uploading}>
                  选择本地文件
                </button>
                <button type="button" className="upload-btn" onClick={pickFolder} disabled={uploading}>
                  选择本地文件夹
                </button>
              </div>
            )}
            {hasElectron && (
              <p className="upload-hint">大项目直接选文件夹，后端在你本机读盘解析，免上传。</p>
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
              {draft.points.map((p, i) => (
                <li key={i}>
                  <Tag>L{p.layer}</Tag> {p.name}
                  {p.note ? ` · ${p.note}` : ''}
                </li>
              ))}
            </ul>
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
        返回砚台
      </button>
    </div>
  );
}
