export interface ProviderPreset {
  id: string;
  label: string;
  provider: string;
  baseUrl: string;
  models: string[];
  builtIn: boolean;
}

const STORAGE_KEY = 'mianba.ai.custom-providers.v1';

/**
 * 常用 OpenAI 兼容 Provider。
 * 模型清单只放适合本项目聊天/结构化输出的模型；厂商新模型仍可通过“手动输入”使用。
 */
export const BUILT_IN_PROVIDER_PRESETS: ProviderPreset[] = [
  {
    id: 'builtin:deepseek',
    label: 'DeepSeek',
    provider: 'deepseek',
    baseUrl: 'https://api.deepseek.com',
    models: ['deepseek-v4-flash', 'deepseek-v4-pro'],
    builtIn: true,
  },
  {
    id: 'builtin:kimi',
    label: 'Kimi（月之暗面）',
    provider: 'kimi',
    baseUrl: 'https://api.moonshot.cn/v1',
    models: [
      'kimi-latest',
      'kimi-k2.5',
      'kimi-k2-thinking',
      'kimi-k2-thinking-turbo',
      'kimi-k2-turbo-preview',
      'moonshot-v1-auto',
      'moonshot-v1-8k',
      'moonshot-v1-32k',
      'moonshot-v1-128k',
    ],
    builtIn: true,
  },
  {
    id: 'builtin:qwen',
    label: 'Qwen（阿里云百炼）',
    provider: 'dashscope',
    baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
    models: [
      'qwen3.8-max',
      'qwen3.8-flash',
      'qwen3.7-plus',
      'qwen-plus',
      'qwen-max',
      'qwen-flash',
      'qwen-turbo',
      'qwen3-coder-plus',
      'qwen3.5-omni-plus',
    ],
    builtIn: true,
  },
  {
    id: 'builtin:openai',
    label: 'OpenAI',
    provider: 'openai',
    baseUrl: 'https://api.openai.com/v1',
    models: [
      'gpt-5.6',
      'gpt-5.6-sol',
      'gpt-5.6-terra',
      'gpt-5.6-luna',
      'gpt-5.1',
      'gpt-5-mini',
      'gpt-4.1',
      'gpt-4.1-mini',
      'gpt-4o',
      'gpt-4o-mini',
    ],
    builtIn: true,
  },
  {
    id: 'builtin:zhipu',
    label: '智谱 GLM',
    provider: 'zhipu',
    baseUrl: 'https://open.bigmodel.cn/api/paas/v4',
    models: ['glm-5.2', 'glm-5.1', 'glm-5-turbo', 'glm-4.7', 'glm-4.5-air'],
    builtIn: true,
  },
];

function isString(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0;
}

function normalizeStoredPreset(value: unknown): ProviderPreset | null {
  if (!value || typeof value !== 'object') return null;
  const raw = value as Partial<ProviderPreset>;
  if (!isString(raw.id) || !isString(raw.label) || !isString(raw.provider) || !isString(raw.baseUrl)) {
    return null;
  }
  const models = Array.isArray(raw.models)
    ? [...new Set(raw.models.filter(isString).map((item) => item.trim()))]
    : [];
  if (models.length === 0) return null;
  return {
    id: raw.id,
    label: raw.label.trim(),
    provider: raw.provider.trim(),
    baseUrl: raw.baseUrl.trim().replace(/\/$/, ''),
    models,
    builtIn: false,
  };
}

/** 自定义 Provider 预设只读取本机 localStorage，数据中不包含 API Key。 */
export function loadCustomProviderPresets(): ProviderPreset[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return [];
    const parsed: unknown = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed.map(normalizeStoredPreset).filter((item): item is ProviderPreset => item !== null);
  } catch {
    return [];
  }
}

/** 覆盖本机的自定义 Provider 预设；永不接触 API Key 或后端接口。 */
export function saveCustomProviderPresets(presets: ProviderPreset[]): void {
  try {
    const safe = presets
      .map(normalizeStoredPreset)
      .filter((item): item is ProviderPreset => item !== null)
      .map(({ id, label, provider, baseUrl, models }) => ({ id, label, provider, baseUrl, models }));
    localStorage.setItem(STORAGE_KEY, JSON.stringify(safe));
  } catch {
    // 隐私模式或存储空间不可用时，当前会话仍可继续使用，只是不做持久化。
  }
}

export function createCustomProviderPreset(
  label: string,
  baseUrl: string,
  model: string,
): ProviderPreset {
  const normalizedLabel = label.trim();
  return {
    id: `custom:${Date.now()}:${Math.random().toString(36).slice(2, 8)}`,
    label: normalizedLabel,
    provider: normalizedLabel,
    baseUrl: baseUrl.trim().replace(/\/$/, ''),
    models: [model.trim()],
    builtIn: false,
  };
}
