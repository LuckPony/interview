// 外观偏好：主题（白天 / 黑夜 / 跟随系统）+ 多处字号（整体 / 题干 / 正文讲解 / 代码）。
// 纯前端，存 localStorage，改完立即生效、无需重启。

export type ThemeMode = 'light' | 'dark' | 'system';

export interface AppearancePrefs {
  /** 主题模式：跟随系统时按 prefers-color-scheme 解析 */
  theme: ThemeMode;
  /** 整体字号档位：0 小(14px) | 1 标准(16px) | 2 大(18px) | 3 特大(20px) */
  fontScale: number;
  /** 题干字号档位：0 小 | 1 标准 | 2 大 */
  stemScale: number;
  /** 正文 / 讲解字号档位：0 小 | 1 标准 | 2 大 */
  bodyScale: number;
  /** 代码字号档位：0 小 | 1 标准 | 2 大 */
  codeScale: number;
}

const KEY = 'mianba.appearance';

export const DEFAULT_PREFS: AppearancePrefs = {
  theme: 'system',
  fontScale: 1,
  stemScale: 1,
  bodyScale: 1,
  codeScale: 1,
};

const FONT_PX = [14, 16, 18, 20];      // 整体字号档位 → html 根字号
const AREA_MULT = [0.9, 1, 1.15];      // 分项字号档位 → 乘数

function clampNum(v: unknown, min: number, max: number, def: number): number {
  const n = typeof v === 'number' && Number.isFinite(v) ? v : def;
  return Math.min(max, Math.max(min, Math.round(n)));
}

export function loadPrefs(): AppearancePrefs {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return { ...DEFAULT_PREFS };
    const p = JSON.parse(raw) as Partial<AppearancePrefs>;
    return {
      theme: p.theme === 'light' || p.theme === 'dark' || p.theme === 'system' ? p.theme : DEFAULT_PREFS.theme,
      fontScale: clampNum(p.fontScale, 0, 3, DEFAULT_PREFS.fontScale),
      stemScale: clampNum(p.stemScale, 0, 2, DEFAULT_PREFS.stemScale),
      bodyScale: clampNum(p.bodyScale, 0, 2, DEFAULT_PREFS.bodyScale),
      codeScale: clampNum(p.codeScale, 0, 2, DEFAULT_PREFS.codeScale),
    };
  } catch {
    return { ...DEFAULT_PREFS };
  }
}

export function savePrefs(p: AppearancePrefs): void {
  try { localStorage.setItem(KEY, JSON.stringify(p)); } catch { /* ignore */ }
}

export function resolveTheme(mode: ThemeMode): 'light' | 'dark' {
  if (mode === 'system') {
    return (typeof window !== 'undefined' && window.matchMedia?.('(prefers-color-scheme: dark)').matches)
      ? 'dark' : 'light';
  }
  return mode;
}

/** 把偏好应用到 <html>：data-theme + 根字号 + 分项字号乘数（CSS 变量）。 */
export function applyPrefs(p: AppearancePrefs): void {
  const root = document.documentElement;
  root.setAttribute('data-theme', resolveTheme(p.theme));
  root.style.fontSize = `${FONT_PX[p.fontScale] ?? 16}px`;
  root.style.setProperty('--scale-stem', String(AREA_MULT[p.stemScale] ?? 1));
  root.style.setProperty('--scale-body', String(AREA_MULT[p.bodyScale] ?? 1));
  root.style.setProperty('--scale-code', String(AREA_MULT[p.codeScale] ?? 1));
}
