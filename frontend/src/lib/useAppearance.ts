import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  applyPrefs,
  loadPrefs,
  resolveTheme,
  savePrefs,
  type AppearancePrefs,
} from './appearance';

/** 外观偏好的 React 钩子：读取 + 更新，改完立即应用并持久化。 */
export function useAppearance() {
  const [prefs, setPrefs] = useState<AppearancePrefs>(() => loadPrefs());
  const theme = useMemo(() => resolveTheme(prefs.theme), [prefs.theme]);

  const update = useCallback((patch: Partial<AppearancePrefs>) => {
    setPrefs((prev) => {
      const next = { ...prev, ...patch };
      applyPrefs(next);
      savePrefs(next);
      return next;
    });
  }, []);

  // 首屏/外部改动时应用；跟随系统时监听系统主题变化。
  useEffect(() => {
    applyPrefs(prefs);
    if (prefs.theme !== 'system') return;
    const mq = window.matchMedia?.('(prefers-color-scheme: dark)');
    if (!mq) return;
    const onChange = () => applyPrefs(prefs);
    mq.addEventListener?.('change', onChange);
    return () => mq.removeEventListener?.('change', onChange);
  }, [prefs]);

  return { prefs, theme, update };
}
