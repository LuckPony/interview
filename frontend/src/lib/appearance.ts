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
  /** AI 聊天气泡背景色（CSS 颜色值；null = 跟随主题默认） */
  aiBubbleColor: string | null;
  /** 用户聊天气泡背景色（CSS 颜色值；null = 跟随主题默认） */
  meBubbleColor: string | null;
}

const KEY = 'mianba.appearance';

export const DEFAULT_PREFS: AppearancePrefs = {
  theme: 'system',
  fontScale: 1,
  stemScale: 1,
  bodyScale: 1,
  codeScale: 1,
  aiBubbleColor: null,
  meBubbleColor: null,
};

const FONT_PX = [14, 16, 18, 20];      // 整体字号档位 → html 根字号
const AREA_MULT = [0.9, 1, 1.15];      // 分项字号档位 → 乘数

function clampNum(v: unknown, min: number, max: number, def: number): number {
  const n = typeof v === 'number' && Number.isFinite(v) ? v : def;
  return Math.min(max, Math.max(min, Math.round(n)));
}

function colorOrNull(v: unknown): string | null {
  return typeof v === 'string' && v.trim() ? v : null;
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
      aiBubbleColor: colorOrNull(p.aiBubbleColor),
      meBubbleColor: colorOrNull(p.meBubbleColor),
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

/** 把偏好应用到 <html>：data-theme + 根字号 + 分项字号乘数（CSS 变量）。
 * 聊天气泡颜色同样以 CSS 变量下发，气泡样式优先取自定义色，缺省回退到主题默认；
 * 前景色按背景亮度自动取黑/白，保证浅色背景配深字、深色背景配浅字。 */
export function applyPrefs(p: AppearancePrefs): void {
  const root = document.documentElement;
  root.setAttribute('data-theme', resolveTheme(p.theme));
  root.style.fontSize = `${FONT_PX[p.fontScale] ?? 16}px`;
  root.style.setProperty('--scale-stem', String(AREA_MULT[p.stemScale] ?? 1));
  root.style.setProperty('--scale-body', String(AREA_MULT[p.bodyScale] ?? 1));
  root.style.setProperty('--scale-code', String(AREA_MULT[p.codeScale] ?? 1));
  if (p.aiBubbleColor) {
    root.style.setProperty('--bubble-ai', p.aiBubbleColor);
    root.style.setProperty('--bubble-ai-fg', readableFg(p.aiBubbleColor));
  } else {
    root.style.removeProperty('--bubble-ai');
    root.style.removeProperty('--bubble-ai-fg');
  }
  if (p.meBubbleColor) {
    root.style.setProperty('--bubble-me', p.meBubbleColor);
    root.style.setProperty('--bubble-me-fg', readableFg(p.meBubbleColor));
  } else {
    root.style.removeProperty('--bubble-me');
    root.style.removeProperty('--bubble-me-fg');
  }
}

/** 根据背景色的感知亮度返回可读前景色：#1f2430（深字）或 #ffffff（浅字）。 */
function readableFg(color: string): string {
  const m = /^#?([0-9a-f]{6})$/i.exec(color.trim());
  if (!m) return '#1f2430';
  const v = parseInt(m[1], 16);
  const r = (v >> 16) & 0xff;
  const g = (v >> 8) & 0xff;
  const b = v & 0xff;
  // 相对亮度（sRGB 近似），> 0.6 视为浅色背景 → 深字
  const lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
  return lum > 0.6 ? '#1f2430' : '#ffffff';
}
