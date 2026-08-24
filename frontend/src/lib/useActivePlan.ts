import { useEffect, useState } from 'react';
import type { PlanView } from '../api/types';

/** 全局「当前学习方向」localStorage 键：首页选择后，学习计划/掌握画像/练习等页面默认跟随 */
export const ACTIVE_PLAN_KEY = 'yan.activePlanId';

/** 读取全局「当前学习方向」id（可能不存在或已失效） */
export function readActivePlanId(): number | null {
  const raw = localStorage.getItem(ACTIVE_PLAN_KEY);
  return raw ? Number(raw) : null;
}

/**
 * 当前学习方向（全局共享，localStorage 记忆）。
 * 今日任务 / 问答记录 / 内化复盘都按它过滤；切换需确认弹窗。
 */
export function useActivePlan(plans: PlanView[]) {
  const [activeId, setActiveId] = useState<number | null>(readActivePlanId);

  // 记忆的方向若已不存在（如被删除），回落到第一个。
  // 注意：plans 为空（异步加载中）时不要重置，否则会覆盖用户在首页刚选的方向。
  useEffect(() => {
    setActiveId((cur) => {
      if (cur != null && plans.some((p) => p.id === cur)) return cur;
      if (plans.length === 0) return cur;   // 还在加载，保留用户选择
      const next = plans[0].id;             // plans 非空且存的 id 不存在 → 回落第一个
      localStorage.setItem(ACTIVE_PLAN_KEY, String(next));
      return next;
    });
  }, [plans]);

  const activePlan = plans.find((p) => p.id === activeId) ?? null;

  /** 切换方向：确认弹窗（今日任务 / 问答记录 / 复盘都会切换） */
  const switchPlan = (planId: number) => {
    const target = plans.find((p) => p.id === planId);
    if (!target || planId === activeId) return;
    if (!window.confirm(`切换到学习方向「${target.title}」？\n今日任务、问答记录、内化复盘都会切换为该方向。`)) return;
    localStorage.setItem(ACTIVE_PLAN_KEY, String(planId));
    setActiveId(planId);
  };

  return { activePlan, activeId, switchPlan };
}
