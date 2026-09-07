import { useEffect, useRef, useState } from 'react';
import { ConfigProvider, Segmented, Switch, TimePicker, theme as antdTheme } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import dayjs, { type Dayjs } from 'dayjs';
import { Bell, CalendarDays, Check, ChevronDown, Plus, X } from 'lucide-react';
import { aiSettings, type AiSettingsView } from '../api/drill';
import { Button, Card, Loading } from '../components/ui';
import { ApiError } from '../api/client';
import { useAppearance } from '../lib/useAppearance';
import type { ThemeMode } from '../lib/appearance';
import {
  BUILT_IN_PROVIDER_PRESETS,
  createCustomProviderPreset,
  loadCustomProviderPresets,
  saveCustomProviderPresets,
  type ProviderPreset,
} from '../lib/providerPresets';
import './Settings.css';

function msg(e: unknown): string {
  return e instanceof ApiError ? e.message : '保存失败';
}

function isValidProviderUrl(value: string): boolean {
  try {
    const url = new URL(value);
    return url.protocol === 'https:' || url.protocol === 'http:';
  } catch {
    return false;
  }
}

/** 是否运行在桌面端（Electron 提供了本机 key 桥）。 */
const isDesktop = typeof window !== 'undefined' && !!window.electronAPI?.getLlmKey;
const CREATE_PROVIDER_VALUE = '__create_provider__';
const MANUAL_MODEL_VALUE = '__manual_model__';

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

/** 颜色选择（带「恢复默认」）。空值 = 跟随主题默认。 */
function ColorField({
  label,
  value,
  onChange,
}: {
  label: string;
  value: string | null;
  onChange: (v: string | null) => void;
}) {
  return (
    <div className="field">
      <span className="field-label">{label}</span>
      <div className="color-field">
        <input
          type="color"
          className="color-input"
          value={value ?? '#888888'}
          onChange={(e) => onChange(e.target.value)}
          aria-label={label}
        />
        <span className="color-value">{value ?? '跟随主题'}</span>
        {value && (
          <button type="button" className="seg-btn" onClick={() => onChange(null)}>
            恢复默认
          </button>
        )}
      </div>
    </div>
  );
}

function ProviderPicker({
  value,
  options,
  onChange,
  onDelete,
}: {
  value: string;
  options: ProviderPreset[];
  onChange: (id: string) => void;
  onDelete: (id: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const selected = options.find((item) => item.id === value);

  useEffect(() => {
    if (!open) return;
    const close = (event: PointerEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) setOpen(false);
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false);
    };
    document.addEventListener('pointerdown', close);
    document.addEventListener('keydown', escape);
    return () => {
      document.removeEventListener('pointerdown', close);
      document.removeEventListener('keydown', escape);
    };
  }, [open]);

  const choose = (id: string) => {
    onChange(id);
    setOpen(false);
  };

  return (
    <div className={`provider-picker${open ? ' is-open' : ''}`} ref={rootRef}>
      <button
        type="button"
        className="provider-picker-trigger"
        aria-haspopup="listbox"
        aria-expanded={open}
        onClick={() => setOpen((current) => !current)}
      >
        <span>{value === CREATE_PROVIDER_VALUE ? '创建新的 Provider' : selected?.label ?? '请选择 Provider'}</span>
        <ChevronDown size={17} strokeWidth={1.8} aria-hidden />
      </button>

      {open && (
        <div className="provider-picker-menu" role="listbox" aria-label="Provider">
          {options.map((item) => (
            <div
              key={item.id}
              className={`provider-picker-option${item.id === value ? ' is-selected' : ''}`}
              role="option"
              aria-selected={item.id === value}
            >
              <button type="button" className="provider-picker-option-main" onClick={() => choose(item.id)}>
                <span>{item.label}</span>
                <small>{item.builtIn ? '内置' : '仅本机'}</small>
              </button>
              {!item.builtIn && (
                <button
                  type="button"
                  className="provider-picker-delete"
                  aria-label={`删除自定义 Provider：${item.label}`}
                  title="删除本地 Provider"
                  onClick={(event) => {
                    event.stopPropagation();
                    onDelete(item.id);
                  }}
                >
                  <X size={14} strokeWidth={2} aria-hidden />
                </button>
              )}
            </div>
          ))}
          <button
            type="button"
            className={`provider-picker-create${value === CREATE_PROVIDER_VALUE ? ' is-selected' : ''}`}
            role="option"
            aria-selected={value === CREATE_PROVIDER_VALUE}
            onClick={() => choose(CREATE_PROVIDER_VALUE)}
          >
            <Plus size={16} strokeWidth={2} aria-hidden />
            创建新的 Provider
          </button>
        </div>
      )}
    </div>
  );
}

/** 外观设置：主题模式 + 多处字号 + 聊天气泡颜色。存本机 localStorage，改完立即生效、无需保存。 */
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
      <ColorField
        label="AI 聊天气泡颜色"
        value={prefs.aiBubbleColor}
        onChange={(v) => update({ aiBubbleColor: v })}
      />
      <ColorField
        label="用户聊天气泡颜色"
        value={prefs.meBubbleColor}
        onChange={(v) => update({ meBubbleColor: v })}
      />
      <p className="settings-note">主题、字号与气泡颜色只保存在本机浏览器，改完立即生效。</p>
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

const WEEKDAYS = [
  { value: 1, label: '周一' }, { value: 2, label: '周二' }, { value: 3, label: '周三' },
  { value: 4, label: '周四' }, { value: 5, label: '周五' }, { value: 6, label: '周六' },
  { value: 0, label: '周日' },
];

function ReminderCard() {
  const api = window.electronAPI;
  const [dark, setDark] = useState(() => document.documentElement.dataset.theme === 'dark');
  const [enabled, setEnabled] = useState(true);
  const [frequency, setFrequency] = useState<'DAILY' | 'WEEKLY'>('DAILY');
  const [weekdays, setWeekdays] = useState<number[]>([1, 2, 3, 4, 5]);
  const [time, setTime] = useState('20:00');
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    if (!api?.getReminder) return;
    // 时间选择器的弹层挂载到 body，也要跟随工作台主题，不能留在旧蓝色/浅色主题。
    const observer = new MutationObserver(() => setDark(document.documentElement.dataset.theme === 'dark'));
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
    return () => observer.disconnect();
  }, [api]);

  useEffect(() => {
    api?.getReminder?.().then((v) => {
      setEnabled(v.enabled);
      setTime(v.time);
      setFrequency(v.frequency ?? 'DAILY');
      setWeekdays(v.weekdays?.length ? v.weekdays : [1, 2, 3, 4, 5]);
    }).catch(() => {});
  }, [api]);

  if (!api?.getReminder) return null;
  const toggleWeekday = (day: number) => setWeekdays((current) => current.includes(day)
    ? (current.length > 1 ? current.filter((v) => v !== day) : current)
    : [...current, day]);
  const save = async () => {
    const v = await api.setReminder({ enabled, time, frequency, weekdays });
    setEnabled(v.enabled); setTime(v.time); setFrequency(v.frequency); setWeekdays(v.weekdays);
    setSaved(true);
    window.setTimeout(() => setSaved(false), 1800);
  };
  const timeValue = dayjs(`2000-01-01 ${time}`);
  const onTimeChange = (value: Dayjs | null) => value && setTime(value.format('HH:mm'));
  return (
    <ConfigProvider
      locale={zhCN}
      theme={{
        algorithm: dark ? antdTheme.darkAlgorithm : antdTheme.defaultAlgorithm,
        token: {
          colorPrimary: dark ? '#8ac0b2' : '#37796d',
          colorBgContainer: dark ? '#22322c' : '#ffffff',
          colorBgElevated: dark ? '#22322c' : '#ffffff',
          borderRadius: 10,
          controlHeight: 40,
          fontFamily: 'var(--font-sans)',
        },
      }}
    >
      <Card className="settings-card reminder-card">
        <div className="reminder-heading">
          <span className="reminder-icon"><Bell size={19} /></span>
          <div className="reminder-heading-copy">
            <h2 className="settings-section-title">学习提醒</h2>
            <p>在有待办任务时按计划发送系统通知</p>
          </div>
          <Switch checked={enabled} onChange={setEnabled} aria-label="启用学习提醒" />
        </div>

        <div className="reminder-form">
          <div className="reminder-field">
            <span className="field-label">提醒周期</span>
            <Segmented
              block
              value={frequency}
              disabled={!enabled}
              options={[{ value: 'DAILY', label: '每天' }, { value: 'WEEKLY', label: '每周' }]}
              onChange={(value) => setFrequency(value as 'DAILY' | 'WEEKLY')}
            />
          </div>

          {frequency === 'WEEKLY' && (
            <div className="reminder-field reminder-week-field">
              <span className="field-label"><CalendarDays size={15} /> 提醒日期</span>
              <div className="weekday-picker" role="group" aria-label="每周提醒日期">
                {WEEKDAYS.map((day) => (
                  <button type="button" disabled={!enabled} className={weekdays.includes(day.value) ? 'is-on' : ''} key={day.value} onClick={() => toggleWeekday(day.value)}>
                    {day.label}
                  </button>
                ))}
              </div>
            </div>
          )}

          <div className="reminder-field">
            <span className="field-label">提醒时间</span>
            <TimePicker
              className="reminder-time"
              value={timeValue}
              disabled={!enabled}
              format="HH:mm"
              minuteStep={5}
              allowClear={false}
              inputReadOnly
              showNow={false}
              popupClassName="reminder-time-popup"
              onChange={onTimeChange}
            />
          </div>
        </div>

        <div className="reminder-footer">
          <div className="settings-actions">
            <Button onClick={save} disabled={!enabled}>保存提醒</Button>
            <Button variant="ghost" onClick={() => api.testReminder()}>测试通知</Button>
            {saved && <span className="settings-saved"><Check size={14} /> 已保存</span>}
          </div>
          <p className="settings-note">窗口关闭并驻留托盘后，提醒仍会按计划运行。</p>
        </div>
      </Card>
    </ConfigProvider>
  );
}

/** 设置页：外观（主题/字号）+ AI 模型 provider / base-url / api-key / model / temperature。
 *  桌面端：key 只存在本机（不传服务器）；Web 端：key 按登录用户保存到服务器（每人一份，互不可见）。 */
export function Settings() {
  const [cfg, setCfg] = useState<AiSettingsView | null>(null);
  const [customProviders, setCustomProviders] = useState<ProviderPreset[]>(loadCustomProviderPresets);
  const [selectedProviderId, setSelectedProviderId] = useState('');
  const [provider, setProvider] = useState('');
  const [baseUrl, setBaseUrl] = useState('');
  const [model, setModel] = useState('');
  const [manualModel, setManualModel] = useState(false);
  const [apiKey, setApiKey] = useState('');
  const [temperature, setTemperature] = useState('0.7');
  const [reasoningEffort, setReasoningEffort] = useState('low');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  const [saved, setSaved] = useState(false);
  const [providerNotice, setProviderNotice] = useState('');

  const providerOptions = [...BUILT_IN_PROVIDER_PRESETS, ...customProviders];
  const selectedPreset = providerOptions.find((item) => item.id === selectedProviderId);
  const isCreatingProvider = selectedProviderId === CREATE_PROVIDER_VALUE;

  useEffect(() => {
    aiSettings
      .get()
      .then((v) => {
        const knownProviders = [...BUILT_IN_PROVIDER_PRESETS, ...loadCustomProviderPresets()];
        const normalizedBaseUrl = v.baseUrl.replace(/\/$/, '');
        const exact = knownProviders.find((item) => (
          item.provider.toLowerCase() === v.provider.toLowerCase()
          && item.baseUrl.replace(/\/$/, '') === normalizedBaseUrl
        ));
        const matched = exact ?? knownProviders.find(
          (item) => item.provider.toLowerCase() === v.provider.toLowerCase(),
        );
        setCfg(v);
        setProvider(v.provider);
        setBaseUrl(v.baseUrl);
        setModel(v.model);
        setSelectedProviderId(matched?.id ?? CREATE_PROVIDER_VALUE);
        setManualModel(!matched?.models.includes(v.model));
        setTemperature(String(v.temperature));
        setReasoningEffort(v.reasoningEffort || 'low');
      })
      .catch((e) => setErr(msg(e)));
  }, []);

  const selectProvider = (id: string) => {
    setSelectedProviderId(id);
    setSaved(false);
    setProviderNotice('');
    setErr('');
    if (id === CREATE_PROVIDER_VALUE) {
      setProvider('');
      setBaseUrl('');
      setModel('');
      setManualModel(true);
      return;
    }
    const preset = providerOptions.find((item) => item.id === id);
    if (!preset) return;
    setProvider(preset.provider);
    setBaseUrl(preset.baseUrl);
    setModel(preset.models[0] ?? '');
    setManualModel(false);
  };

  const deleteCustomProvider = (id: string) => {
    const removed = customProviders.find((item) => item.id === id);
    const next = customProviders.filter((item) => item.id !== id);
    setCustomProviders(next);
    saveCustomProviderPresets(next);
    setProviderNotice(removed ? `已从本机删除“${removed.label}”及其 URL、模型记录。` : '已删除本地 Provider。');
    setSaved(false);
    if (selectedProviderId === id) {
      const fallback = BUILT_IN_PROVIDER_PRESETS[0];
      setSelectedProviderId(fallback.id);
      setProvider(fallback.provider);
      setBaseUrl(fallback.baseUrl);
      setModel(fallback.models[0]);
      setManualModel(false);
    }
  };

  const save = async () => {
    setErr('');
    setSaved(false);
    setProviderNotice('');

    const normalizedProvider = provider.trim();
    const normalizedBaseUrl = baseUrl.trim().replace(/\/$/, '');
    const normalizedModel = model.trim();
    const normalizedTemperature = Number(temperature);
    if (!normalizedProvider || !normalizedBaseUrl || !normalizedModel) {
      setErr('请完整填写 Provider、Base URL 和模型名');
      return;
    }
    if (!isValidProviderUrl(normalizedBaseUrl)) {
      setErr('Base URL 必须是有效的 http:// 或 https:// 地址');
      return;
    }
    if (!Number.isFinite(normalizedTemperature) || normalizedTemperature < 0 || normalizedTemperature > 1) {
      setErr('Temperature 必须是 0 到 1 之间的数字');
      return;
    }

    let nextCustomProviders = customProviders;
    let nextSelectedProviderId = selectedProviderId;
    let localPresetChanged = false;

    if (isCreatingProvider) {
      const duplicate = providerOptions.some((item) => (
        item.provider.toLowerCase() === normalizedProvider.toLowerCase()
        || item.label.toLowerCase() === normalizedProvider.toLowerCase()
      ));
      if (duplicate) {
        setErr('这个 Provider 已经存在，请直接从下拉框中选择');
        return;
      }
      const created = createCustomProviderPreset(normalizedProvider, normalizedBaseUrl, normalizedModel);
      nextCustomProviders = [...customProviders, created];
      nextSelectedProviderId = created.id;
      localPresetChanged = true;
    } else {
      const activeCustom = customProviders.find((item) => item.id === selectedProviderId);
      if (activeCustom && !activeCustom.models.includes(normalizedModel)) {
        nextCustomProviders = customProviders.map((item) => item.id === activeCustom.id
          ? { ...item, models: [...item.models, normalizedModel] }
          : item);
        localPresetChanged = true;
      }
    }

    setBusy(true);
    try {
      const trimmed = apiKey.trim();
      if (isDesktop && trimmed) {
        // 桌面端：key 只存本机；模型设置同步到服务器（key 留空 = 服务器不存/不改 key）
        await window.electronAPI!.setLlmKey!(trimmed);
        await aiSettings.update({
          provider: normalizedProvider,
          baseUrl: normalizedBaseUrl,
          model: normalizedModel,
          apiKey: '',
          temperature: normalizedTemperature,
          reasoningEffort,
        });
      } else {
        // Web 端 / 桌面端留空：key 存到当前账号（服务器按用户隔离）
        await aiSettings.update({
          provider: normalizedProvider,
          baseUrl: normalizedBaseUrl,
          model: normalizedModel,
          apiKey: trimmed,
          temperature: normalizedTemperature,
          reasoningEffort,
        });
      }
      if (localPresetChanged) {
        setCustomProviders(nextCustomProviders);
        saveCustomProviderPresets(nextCustomProviders);
        setSelectedProviderId(nextSelectedProviderId);
        setManualModel(false);
        setProviderNotice('自定义 Provider 已保存到本机；API Key 未写入该本地预设。');
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

      <div className="settings-grid">
        <AppearanceCard />
        <ReminderCard />

        {cfg !== null && !cfg.hasApiKey && (
          <div className="banner warn settings-wide">
          尚未配置 API Key：AI 出题、判分、复盘、计划生成等都会不可用。请先在下表填写你自己的
          API Key（Web 端按账号保存，互不可见；桌面端只存本机）。
          </div>
        )}

        {cfg === null ? (
          <div className="settings-wide"><Loading label="读取设置…" /></div>
        ) : (
          <Card className="settings-card">
          <h2 className="settings-section-title">模型</h2>
          <div className="field">
            <span className="field-label">Provider</span>
            <ProviderPicker
              value={selectedProviderId}
              options={providerOptions}
              onChange={selectProvider}
              onDelete={deleteCustomProvider}
            />
            {isCreatingProvider && (
              <input
                className="note-input provider-custom-input"
                value={provider}
                onChange={(e) => setProvider(e.target.value)}
                placeholder="输入新的 Provider 名称"
                autoComplete="off"
              />
            )}
            {providerNotice && <span className="provider-local-notice">{providerNotice}</span>}
          </div>

          <label className="field">
            <span className="field-label">Base URL（OpenAI 兼容端点）</span>
            <input
              className={`note-input${isCreatingProvider ? '' : ' is-prefilled'}`}
              value={baseUrl}
              onChange={(e) => setBaseUrl(e.target.value)}
              placeholder="https://api.example.com/v1"
              readOnly={!isCreatingProvider}
            />
          </label>

          <div className="field">
            <span className="field-label">模型名</span>
            {!isCreatingProvider && selectedPreset ? (
              <>
                <select
                  className="note-input provider-model-select"
                  value={manualModel ? MANUAL_MODEL_VALUE : model}
                  onChange={(e) => {
                    if (e.target.value === MANUAL_MODEL_VALUE) {
                      setManualModel(true);
                      setModel('');
                    } else {
                      setManualModel(false);
                      setModel(e.target.value);
                    }
                  }}
                >
                  {selectedPreset.models.map((item) => <option key={item} value={item}>{item}</option>)}
                  <option value={MANUAL_MODEL_VALUE}>手动输入其他模型…</option>
                </select>
                {manualModel && (
                  <input
                    className="note-input provider-custom-input"
                    value={model}
                    onChange={(e) => setModel(e.target.value)}
                    placeholder="输入厂商支持的模型 ID"
                    autoComplete="off"
                  />
                )}
              </>
            ) : (
              <input
                className="note-input"
                value={model}
                onChange={(e) => setModel(e.target.value)}
                placeholder="输入模型 ID"
                autoComplete="off"
              />
            )}
          </div>

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

          <label className="field">
            <span className="field-label">思考强度（仅对有思考强度的模型生效）</span>
            <select className="note-input" value={reasoningEffort} onChange={(e) => setReasoningEffort(e.target.value)}>
              <option value="low">低 · 思考最短、生成最快</option>
              <option value="medium">中 · 思考与速度均衡</option>
              <option value="high">高 · 深度思考、更慢</option>
              <option value="auto">跟随模型默认（可能思考更久）</option>
            </select>
            <p className="settings-note">
              控制讲解/答疑/对话的推理深度。DeepSeek V4、GLM、OpenAI 推理模型默认高，会让思考很长；
              设为「低」能明显缩短等待。非推理模型或没有该参数的模型会忽略此项。
            </p>
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
          <p className="settings-note provider-security-note">
            自定义 Provider 的名称、URL 和模型列表只保存在本机；API Key 不会写入本地预设。
          </p>
          </Card>
        )}

        {isDesktop && <AboutCard />}
      </div>
    </div>
  );
}
