import { useEffect, useState } from 'react';
import { Check } from 'lucide-react';
import { aiSettings, type AiSettingsView } from '../api/drill';
import { Button, Card, Loading } from '../components/ui';
import { ApiError } from '../api/client';
import { useAppearance } from '../lib/useAppearance';
import type { ThemeMode } from '../lib/appearance';
import './Settings.css';

function msg(e: unknown): string {
  return e instanceof ApiError ? e.message : '保存失败';
}

/** 是否运行在桌面端（Electron 提供了本机 key 桥）。 */
const isDesktop = typeof window !== 'undefined' && !!window.electronAPI?.getLlmKey;

/* —— 外观（主题 + 字号）选项 —— */
const THEME_OPTS: { value: ThemeMode; label: string }[] = [
  { value: 'light', label: '白天' },
  { value: 'dark', label: '黑夜' },
  { value: 'system', label: '跟随系统' },
];
const SCALE4_OPTS: { value: number; label: string }[] = [
  { value: 0, label: '小' },
  { value: 1, label: '标准' },
  { value: 2, label: '大' },
  { value: 3, label: '特大' },
];
const SCALE3_OPTS: { value: number; label: string }[] = [
  { value: 0, label: '小' },
  { value: 1, label: '标准' },
  { value: 2, label: '大' },
];

/** 分段选择器（一排互斥按钮）。 */
function Seg<T extends string | number>({
  label,
  value,
  options,
  onChange,
}: {
  label: string;
  value: T;
  options: { value: T; label: string }[];
  onChange: (v: T) => void;
}) {
  return (
    <div className="field">
      <span className="field-label">{label}</span>
      <div className="seg" role="group" aria-label={label}>
        {options.map((o) => (
          <button
            key={String(o.value)}
            type="button"
            className={`seg-btn${o.value === value ? ' is-on' : ''}`}
            onClick={() => onChange(o.value)}
          >
            {o.label}
          </button>
        ))}
      </div>
    </div>
  );
}

/** 外观设置：主题模式 + 多处字号。存本机 localStorage，改完立即生效、无需保存。 */
function AppearanceCard() {
  const { prefs, update } = useAppearance();
  return (
    <Card className="settings-card">
      <h2 className="settings-section-title">外观</h2>
      <Seg
        label="主题模式"
        value={prefs.theme}
        options={THEME_OPTS}
        onChange={(v) => update({ theme: v })}
      />
      <Seg
        label="整体字号"
        value={prefs.fontScale}
        options={SCALE4_OPTS}
        onChange={(v) => update({ fontScale: v })}
      />
      <Seg
        label="题干字号"
        value={prefs.stemScale}
        options={SCALE3_OPTS}
        onChange={(v) => update({ stemScale: v })}
      />
      <Seg
        label="正文 · 讲解字号"
        value={prefs.bodyScale}
        options={SCALE3_OPTS}
        onChange={(v) => update({ bodyScale: v })}
      />
      <Seg
        label="代码字号"
        value={prefs.codeScale}
        options={SCALE3_OPTS}
        onChange={(v) => update({ codeScale: v })}
      />
      <p className="settings-note">主题与字号只保存在本机浏览器，改完立即生效。</p>
    </Card>
  );
}

function updateStatusText(s: UpdateStatus | null): string {
  if (!s) return '';
  switch (s.phase) {
    case 'checking':
      return '正在检查更新…';
    case 'available':
      return `发现新版本 v${s.version}，点击「下载更新」开始下载`;
    case 'downloading':
      return '正在下载更新…';
    case 'downloaded':
      return `新版本 v${s.version} 已下载完成`;
    case 'not-available':
      return `当前已是最新版本${s.version ? ` v${s.version}` : ''}`;
    case 'error':
      return `更新失败：${s.message ?? ''}`;
    default:
      return '';
  }
}

/** 关于：版本号 + 检查更新（仅桌面端；网页态没有 Electron 桥，不渲染）。
 *  流程：检查更新（只查）→ 下载更新（按平台下载正确格式）→ 立即更新。 */
function AboutCard() {
  const [version, setVersion] = useState('');
  const [platform, setPlatform] = useState('');
  const [status, setStatus] = useState<UpdateStatus | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!isDesktop) return;
    window.electronAPI!.getVersion!().then(setVersion).catch(() => {});
    window.electronAPI!.getPlatform!().then(setPlatform).catch(() => {});
    return window.electronAPI!.onUpdateStatus!((s) => setStatus(s));
  }, []);

  const check = async () => {
    if (!isDesktop) return;
    setBusy(true);
    setStatus({ phase: 'checking' });
    const r = await window.electronAPI!.checkForUpdates!();
    if (r?.error) setStatus({ phase: 'error', message: r.error });
    setBusy(false);
  };

  const download = async () => {
    if (!isDesktop) return;
    setBusy(true);
    setStatus({ phase: 'downloading', percent: 0 });
    const r = await window.electronAPI!.downloadUpdate!();
    if (r?.error) setStatus({ phase: 'error', message: r.error });
    setBusy(false);
  };

  const install = () => {
    window.electronAPI!.installUpdate!().then((r) => {
      if (r?.error) setStatus({ phase: 'error', message: r.error });
    });
  };

  const isMac = platform === 'darwin';
  const phase = status?.phase;
  const text = updateStatusText(status);
  const downloadedText = isMac
    ? `新版本 v${status?.version} 已下载完成，点击「立即更新」打开 dmg 安装包，拖进「应用程序」覆盖即可`
    : text;

  return (
    <Card className="settings-card">
      <h2 className="settings-section-title">关于</h2>
      <div className="about-row">
        <span className="about-label">版本</span>
        <span className="about-version">{version ? `v${version}` : '—'}</span>
        <Button variant="ghost" onClick={check} disabled={busy || phase === 'downloading'}>
          {busy && phase === 'checking' ? '检查中…' : '检查更新'}
        </Button>
      </div>
      {text && (
        <p className={`update-status${phase === 'downloaded' ? ' is-ready' : ''}`}>
          {phase === 'downloaded' ? downloadedText : text}
        </p>
      )}
      {phase === 'downloading' && (
        <progress className="update-progress" value={status?.percent ?? 0} max={100} />
      )}
      <div className="settings-actions">
        {phase === 'available' && (
          <Button onClick={download} disabled={busy}>
            {busy ? '准备下载…' : '下载更新'}
          </Button>
        )}
        {phase === 'downloading' && (
          <Button disabled>下载中 {status?.percent ?? 0}%</Button>
        )}
        {phase === 'downloaded' && (
          <Button onClick={install}>立即更新</Button>
        )}
      </div>
    </Card>
  );
}

function ReminderCard() {
  const api = window.electronAPI;
  const [enabled, setEnabled] = useState(true);
  const [time, setTime] = useState('20:00');
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    api?.getReminder?.().then((v) => { setEnabled(v.enabled); setTime(v.time); }).catch(() => {});
  }, [api]);

  if (!api?.getReminder) return null;
  const save = async () => {
    const v = await api.setReminder({ enabled, time });
    setEnabled(v.enabled); setTime(v.time); setSaved(true);
    window.setTimeout(() => setSaved(false), 1800);
  };
  return (
    <Card className="settings-card">
      <h2 className="settings-section-title">学习提醒</h2>
      <label className="reminder-toggle">
        <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
        应用挂在后台时，到点发送系统通知
      </label>
      <label className="field">
        <span className="field-label">每天提醒时间</span>
        <input type="time" value={time} onChange={(e) => setTime(e.target.value)} disabled={!enabled} />
      </label>
      <div className="settings-actions">
        <Button onClick={save}>保存提醒</Button>
        <Button variant="ghost" onClick={() => api.testReminder()}>测试通知</Button>
        {saved && <span className="settings-saved"><Check size={14} /> 已保存</span>}
      </div>
      <p className="settings-note">关闭窗口会隐藏到托盘，不会退出应用；只有托盘中选择“彻底退出”后提醒才会停止。</p>
    </Card>
  );
}

/** 设置页：外观（主题/字号）+ AI 模型 provider / base-url / api-key / model / temperature。
 *  桌面端：key 只存在本机（不传服务器）；Web 端：key 按登录用户保存到服务器（每人一份，互不可见）。 */
export function Settings() {
  const [cfg, setCfg] = useState<AiSettingsView | null>(null);
  const [provider, setProvider] = useState('');
  const [baseUrl, setBaseUrl] = useState('');
  const [model, setModel] = useState('');
  const [apiKey, setApiKey] = useState('');
  const [temperature, setTemperature] = useState('0.7');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    aiSettings
      .get()
      .then((v) => {
        setCfg(v);
        setProvider(v.provider);
        setBaseUrl(v.baseUrl);
        setModel(v.model);
        setTemperature(String(v.temperature));
      })
      .catch((e) => setErr(msg(e)));
  }, []);

  const save = async () => {
    setBusy(true);
    setErr('');
    setSaved(false);
    try {
      const trimmed = apiKey.trim();
      if (isDesktop && trimmed) {
        // 桌面端：key 只存本机；模型设置同步到服务器（key 留空 = 服务器不存/不改 key）
        await window.electronAPI!.setLlmKey!(trimmed);
        await aiSettings.update({
          provider: provider.trim(),
          baseUrl: baseUrl.trim(),
          model: model.trim(),
          apiKey: '',
          temperature: Number(temperature) || 0.7,
        });
      } else {
        // Web 端 / 桌面端留空：key 存到当前账号（服务器按用户隔离）
        await aiSettings.update({
          provider: provider.trim(),
          baseUrl: baseUrl.trim(),
          model: model.trim(),
          apiKey: trimmed,
          temperature: Number(temperature) || 0.7,
        });
      }
      setSaved(true);
      setApiKey('');
      // 刷新掩码
      setCfg(await aiSettings.get());
    } catch (e) {
      setErr(msg(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="page">
      <header className="page-head">
        <span className="eyebrow">设置 · SETTINGS</span>
        <h1>设置</h1>
        <p>
          调整界面主题与字号，以及 AI 模型与密钥。外观改动即时生效；模型改动保存后即时生效，无需重启。
          {isDesktop ? ' 当前为桌面端：API Key 仅保存在本机，不会上传服务器。' : ' 当前为 Web 端：API Key 将保存到你的账号下（服务器按用户隔离，不共享默认 key）。'}
        </p>
      </header>

      {err && <div className="banner info">{err}</div>}

      <AppearanceCard />
      <ReminderCard />

      {cfg !== null && !cfg.hasApiKey && (
        <div className="banner warn">
          尚未配置 API Key：AI 出题、判分、复盘、计划生成等都会不可用。请先在下表填写你自己的
          API Key（Web 端按账号保存，互不可见；桌面端只存本机）。
        </div>
      )}

      {cfg === null ? (
        <Loading label="读取设置…" />
      ) : (
        <Card className="settings-card">
          <h2 className="settings-section-title">模型</h2>
          <label className="field">
            <span className="field-label">Provider 名称（仅用于显示）</span>
            <input className="note-input" value={provider} onChange={(e) => setProvider(e.target.value)} placeholder="例如 deepseek / dashscope / kimi" />
          </label>

          <label className="field">
            <span className="field-label">Base URL（OpenAI 兼容端点）</span>
            <input className="note-input" value={baseUrl} onChange={(e) => setBaseUrl(e.target.value)} placeholder="https://api.deepseek.com" />
          </label>

          <label className="field">
            <span className="field-label">模型名</span>
            <input className="note-input" value={model} onChange={(e) => setModel(e.target.value)} placeholder="deepseek-v4-flash" />
          </label>

          <label className="field">
            <span className="field-label">API Key</span>
            <input
              className="note-input"
              type="password"
              value={apiKey}
              onChange={(e) => setApiKey(e.target.value)}
              placeholder={cfg.hasApiKey ? '已配置（留空则不变）' : '填写 API Key'}
              autoComplete="off"
            />
          </label>

          <label className="field">
            <span className="field-label">Temperature（0-1）</span>
            <input className="note-input" type="number" step="0.1" min="0" max="1" value={temperature} onChange={(e) => setTemperature(e.target.value)} />
          </label>

          <div className="settings-actions">
            <Button onClick={save} disabled={busy}>
              {busy ? '保存中…' : '保存设置'}
            </Button>
            {saved && (
              <span className="settings-saved">
                <Check size={14} strokeWidth={2} /> 已保存，下次调用立即生效
              </span>
            )}
          </div>
        </Card>
      )}

      {isDesktop && <AboutCard />}
    </div>
  );
}
