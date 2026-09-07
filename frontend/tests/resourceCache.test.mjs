import assert from 'node:assert/strict';
import test from 'node:test';
import { ResourceCache } from '../src/lib/resourceCache.ts';

const deferred = () => {
  let resolve;
  let reject;
  const promise = new Promise((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
};
const flush = () => new Promise((resolve) => setImmediate(resolve));

test('首页/侧栏/StrictMode 并发请求只读取一次，返回首页复用新鲜缓存', async () => {
  let calls = 0;
  const response = deferred();
  const cache = new ResourceCache({
    today: () => {
      calls += 1;
      return response.promise;
    },
  });
  const stop = cache.watch();
  stop();
  const stopAgain = cache.watch();
  const sidebar = cache.watch(['today']);
  const refresh = cache.refresh();
  await flush();
  assert.equal(calls, 1);
  response.resolve([1, 2]);
  await refresh;
  stopAgain();
  sidebar();
  const snapshot = cache.getSnapshot();
  const stopHome = cache.watch();
  await cache.refresh();
  assert.equal(calls, 1);
  assert.equal(cache.getSnapshot(), snapshot);
  assert.deepEqual(snapshot.data.today, [1, 2]);
  stopHome();
});

test('快接口独立显示，不等待最慢的接口', async () => {
  const slow = deferred();
  const cache = new ResourceCache({ fast: async () => [3], slow: () => slow.promise });
  const refresh = cache.refresh();
  await flush();
  assert.deepEqual(cache.getSnapshot().data, { fast: [3], slow: null });
  assert.equal(cache.getSnapshot().loading, true);
  slow.resolve([5]);
  await refresh;
  assert.equal(cache.getSnapshot().loading, false);
});

test('过期后台刷新保留旧值；请求失败不清空，手动重试可恢复', async () => {
  let now = 1000;
  let response = Promise.resolve([1]);
  let calls = 0;
  const cache = new ResourceCache(
    {
      cards: () => {
        calls += 1;
        return response;
      },
    },
    60_000,
    () => now,
  );
  await cache.refresh();
  now += 60_001;
  const slow = deferred();
  response = slow.promise;
  const refreshing = cache.refresh();
  assert.deepEqual(cache.getSnapshot().data.cards, [1]);
  assert.equal(cache.getSnapshot().loading, true);
  slow.reject(new Error('offline'));
  await refreshing;
  assert.deepEqual(cache.getSnapshot().data.cards, [1]);
  assert.deepEqual(cache.getSnapshot().failed, ['cards']);
  response = Promise.resolve([1, 2]);
  await cache.refresh(undefined, true);
  assert.equal(calls, 3);
  assert.deepEqual(cache.getSnapshot().data.cards, [1, 2]);
  assert.deepEqual(cache.getSnapshot().failed, []);
});

test('写入只刷新相关资源；离开首页时先标记过期，回来再读取', async () => {
  const calls = { cards: 0, today: 0 };
  const cache = new ResourceCache({
    cards: async () => [++calls.cards],
    today: async () => [++calls.today],
  });
  const stop = cache.watch();
  await cache.refresh();
  cache.invalidate(['cards']);
  await cache.refresh();
  assert.deepEqual(calls, { cards: 2, today: 1 });
  stop();
  cache.invalidate(['cards']);
  await flush();
  assert.equal(calls.cards, 2);
  const home = cache.watch();
  assert.deepEqual(cache.getSnapshot().data.cards, [2]);
  await cache.refresh();
  assert.deepEqual(cache.getSnapshot().data.cards, [3]);
  home();
});

test('请求期间发生写入，丢弃过时响应并补查，不能覆盖最新业务状态', async () => {
  const first = deferred();
  let calls = 0;
  const cache = new ResourceCache({ cards: () => (++calls === 1 ? first.promise : Promise.resolve([2])) });
  const stop = cache.watch();
  await flush();
  cache.invalidate(['cards']);
  first.resolve([1]);
  await flush();
  assert.equal(calls, 2);
  assert.deepEqual(cache.getSnapshot().data.cards, [2]);
  stop();
});

test('退出立即清除内存数据，旧账号迟到响应不会回填缓存', async () => {
  const slow = deferred();
  const cache = new ResourceCache({ cards: () => slow.promise });
  const refreshing = cache.refresh();
  cache.dispose();
  slow.resolve(['private']);
  await refreshing;
  assert.equal(cache.getSnapshot().data.cards, null);
  assert.equal(cache.getSnapshot().loading, false);
  await cache.refresh(undefined, true);
  assert.equal(cache.getSnapshot().data.cards, null);
});
