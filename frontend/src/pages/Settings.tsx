import { useEffect, useState } from 'react';
import { Check } from 'lucide-react';
import { aiSettings, type AiSettingsView } from '../api/drill';
import { Button, Card, Loading } from '../components/ui';
import { ApiError } from '../api/client';
import './Settings.css';

function msg(e: unknown): string {
  return e instanceof ApiError ? e.message : '保存失败';
}

/** 是否运行在桌面端（Electron 提供了本机 key 桥）。 */
const isDesktop = typeof window !== 'undefined' && !!window.electronAPI?.getLlmKey;

/** 设置页：配置 AI 模型 provider / base-url / api-key / model / temperature。
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
        <h1>模型设置</h1>
        <p>
          配置你要用的 AI 模型与密钥。支持任何 OpenAI 兼容接口（DeepSeek / 阿里 DashScope / Kimi / 自建等）。改完立即生效，无需重启。
          {isDesktop ? ' 当前为桌面端：API Key 仅保存在本机，不会上传服务器。' : ' 当前为 Web 端：API Key 将保存到你的账号下（服务器按用户隔离，不共享默认 key）。'}
        </p>
      </header>

      {err && <div className="banner info">{err}</div>}

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
    </div>
  );
}
