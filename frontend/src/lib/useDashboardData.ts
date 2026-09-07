import { useCallback, useEffect, useSyncExternalStore } from 'react';
import { getStoredUserId, getToken } from '../api/client';
import { DATA_CHANGED, SESSION_CHANGED } from '../api/dataEvents';
import { drill, studyPlan } from '../api/drill';
import { interviewApi, type InterviewListItem } from '../api/interview';
import { knowledgeApi } from '../api/knowledge';
import type { DailyTaskView, DebtView, KnowledgeCard, PlanView } from '../api/types';
import { ResourceCache } from './resourceCache';

interface DashboardData {
  plans: PlanView[];
  today: DailyTaskView[];
  cards: KnowledgeCard[];
  due: KnowledgeCard[];
  interviews: InterviewListItem[];
  debt: DebtView[];
}
type Section = keyof DashboardData;
const ALL_SECTIONS: readonly Section[] = ['plans', 'today', 'cards', 'due', 'interviews', 'debt'];
export const TODAY_ONLY: readonly Section[] = ['today'];
export const DASHBOARD_LABELS: Record<Section, string> = {
  plans: '学习计划',
  today: '今日任务',
  cards: '知识卡片',
  due: '卡片复习',
  interviews: '面试记录',
  debt: '内化复盘',
};

let current: { identity: string; cache: ResourceCache<DashboardData> } | null = null;

function clearCache() {
  const previous = current;
  current = null;
  previous?.cache.dispose();
}

window.addEventListener(SESSION_CHANGED, clearCache);
// 同浏览器其他窗口退出/切换账号，也不能保留旧用户的首页数据。
window.addEventListener('storage', (event) => {
  if (event.key === null || event.key.startsWith('yan.token:') || event.key.startsWith('yan.userId:')) {
    clearCache();
  }
});

function getCache(userId: string | null): ResourceCache<DashboardData> {
  // token 按 API 地址隔离，且仅用作内存身份比较，不写入额外存储。
  const identity = JSON.stringify([userId, getStoredUserId(), getToken()]);
  if (!current || current.identity !== identity) {
    clearCache();
    current = {
      identity,
      cache: new ResourceCache<DashboardData>({
        plans: studyPlan.list,
        today: drill.today,
        cards: knowledgeApi.list,
        due: knowledgeApi.due,
        interviews: interviewApi.list,
        debt: drill.debt,
      }),
    };
  }
  return current.cache;
}

function affectedSections(path: string): readonly Section[] {
  if (/^\/drill\/\d+\/card$/.test(path)) return ['cards', 'due'];
  if (/^\/knowledge\/(?:cards|capture)(?:\/|$|\?)/.test(path)) return ['cards', 'due', 'plans'];
  if (/^\/(?:drill|study-plan)(?:\/|$)/.test(path)) return ['plans', 'today', 'debt'];
  if (/^\/interviews(?:\/|$)/.test(path)) return ['interviews'];
  return [];
}

window.addEventListener(DATA_CHANGED, (event) => {
  current?.cache.invalidate(affectedSections((event as CustomEvent<string>).detail));
});

export function useDashboardData(userId: string | null, sections: readonly Section[] = ALL_SECTIONS) {
  const cache = getCache(userId);
  const snapshot = useSyncExternalStore(cache.subscribe, cache.getSnapshot);

  useEffect(() => {
    if (!userId) return;
    return cache.watch(sections);
  }, [cache, sections, userId]);

  useEffect(() => {
    if (!userId) return;
    // 返回窗口、跨日及长时间停留时静默校验新鲜度；隐藏网页不轮询六组统计。
    const revalidate = () => {
      if (document.visibilityState === 'visible') cache.refreshWatched();
    };
    window.addEventListener('focus', revalidate);
    document.addEventListener('visibilitychange', revalidate);
    const timer = window.setInterval(revalidate, 60_000);
    return () => {
      window.removeEventListener('focus', revalidate);
      document.removeEventListener('visibilitychange', revalidate);
      window.clearInterval(timer);
    };
  }, [cache, userId]);

  const refresh = useCallback(() => {
    void cache.refresh(sections, true);
  }, [cache, sections]);
  return { ...snapshot, refresh };
}
