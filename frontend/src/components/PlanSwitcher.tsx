import { useEffect, useRef, useState } from 'react';
import { ChevronDown, Check, BookOpen } from 'lucide-react';
import type { PlanView } from '../api/types';

/** 学习方向切换器（组件式下拉）：切换前弹确认；只用在砚台页，其他页面跟随。 */
export function PlanSwitcher({
  plans,
  activeId,
  onSwitch,
}: {
  plans: PlanView[];
  activeId: number | null;
  onSwitch: (id: number) => void;
}) {
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const active = plans.find((p) => p.id === activeId);

  // 点击外部 / 按 Esc 关闭
  useEffect(() => {
    if (!open) return;
    const onDoc = (e: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    document.addEventListener('mousedown', onDoc);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDoc);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  if (plans.length <= 1) return null;

  return (
    <div className="ps" ref={rootRef}>
      <span className="eyebrow">学习方向</span>
      <button
        className="ps-trigger"
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-haspopup="listbox"
        aria-expanded={open}
      >
        <BookOpen size={15} strokeWidth={1.6} className="ps-trigger-ico" />
        <span className="ps-value">{active?.title ?? '选择方向'}</span>
        <ChevronDown size={16} strokeWidth={1.8} className={'ps-caret' + (open ? ' up' : '')} />
      </button>

      {open && (
        <ul className="ps-menu" role="listbox">
          {plans.map((p) => (
            <li key={p.id} role="option" aria-selected={p.id === activeId}>
              <button
                className={'ps-item' + (p.id === activeId ? ' active' : '')}
                type="button"
                onClick={() => {
                  setOpen(false);
                  onSwitch(p.id);
                }}
              >
                <span className="ps-item-name">{p.title}</span>
                {p.id === activeId && <Check size={15} strokeWidth={2.2} className="ps-check" />}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
