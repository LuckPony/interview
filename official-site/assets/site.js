/* ==========================================================================
   面霸 · 官网交互
   1) 动态读取 GitHub 最新 release，自动生成下载链接（发布新版本后页面自动更新）
   2) 截图画廊 + 分类筛选 + 灯箱放大
   3) 杂项交互（回到顶部、年份）
   ========================================================================== */
(function () {
  'use strict';

  const REPO = document.body.dataset.repo || 'LuckPony/interview';
  const API = 'https://api.github.com/repos/' + REPO;

  /* ---------------- 基础工具 ---------------- */
  const $ = (sel, root) => (root || document).querySelectorAll(sel);
  const el = (sel) => document.querySelector(sel);

  function icon(github) {
    return '<svg width="17" height="17" viewBox="0 0 24 24" fill="currentColor"><path d="M12 .297c-6.63 0-12 5.373-12 12 0 5.303 3.438 9.8 8.205 11.385.6.113.82-.258.82-.577 0-.285-.01-1.04-.015-2.04-3.338.724-4.042-1.61-4.042-1.61C4.422 18.07 3.633 17.7 3.633 17.7c-1.087-.744.084-.729.084-.729 1.205.084 1.838 1.236 1.838 1.236 1.07 1.835 2.809 1.305 3.495.998.108-.776.417-1.305.76-1.605-2.665-.3-5.466-1.332-5.466-5.93 0-1.31.465-2.38 1.235-3.22-.135-.303-.54-1.523.105-3.176 0 0 1.005-.322 3.3 1.23.96-.267 1.98-.399 3-.405 1.02.006 2.04.138 3 .405 2.28-1.552 3.285-1.23 3.285-1.23.645 1.653.24 2.873.12 3.176.765.84 1.23 1.91 1.23 3.22 0 4.61-2.805 5.625-5.475 5.92.42.36.81 1.096.81 2.22 0 1.606-.015 2.896-.015 3.286 0 .315.21.69.825.57C20.565 22.092 24 17.592 24 12.297c0-6.627-5.373-12-12-12"/></svg>';
  }
  function downloadIcon() {
    return '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 3v12m0 0 4-4m-4 4-4-4M4 21h16"/></svg>';
  }

  const GH_RELEASES = 'https://github.com/' + REPO + '/releases';

  function toast(msg) {
    const t = el('#toast');
    if (!t) return;
    t.textContent = msg;
    t.classList.add('show');
    clearTimeout(t._tm);
    t._tm = setTimeout(() => t.classList.remove('show'), 2600);
  }

  /* ---------------- 1) GitHub 数据 ---------------- */
  const CACHE_KEY = 'mianba_release_cache_v1';
  const CACHE_TTL = 10 * 60 * 1000; // 10 分钟

  function readCache() {
    try {
      const raw = localStorage.getItem(CACHE_KEY);
      if (!raw) return null;
      const c = JSON.parse(raw);
      if (Date.now() - c.ts > CACHE_TTL) return null;
      return c.data;
    } catch { return null; }
  }
  function writeCache(data) {
    try { localStorage.setItem(CACHE_KEY, JSON.stringify({ ts: Date.now(), data })); } catch {}
  }

  function fmtBytes(n) {
    if (!n && n !== 0) return '';
    const mb = n / (1024 * 1024);
    return (mb >= 100 ? Math.round(mb) : mb.toFixed(1)) + ' MB';
  }

  async function fetchGitHub() {
    const cached = readCache();
    if (cached) return cached;

    // GitHub API 请求加超时（8s），避免弱网下页面一直转圈
    const ac = new AbortController();
    const timer = setTimeout(() => ac.abort(), 8000);
    const opts = { headers: { Accept: 'application/vnd.github+json' }, signal: ac.signal };

    // 并行请求 repo 元数据 + 最新 release（latest 不一定含全部资产，故取 tip 那一条）
    const [repoRes, releaseRes] = await Promise.all([
      fetch(API, opts).catch(() => null),
      fetch(API + '/releases?per_page=1', opts).catch(() => null),
    ]);
    clearTimeout(timer);

    const repo = repoRes.ok ? await repoRes.json() : null;
    let release = null;
    if (releaseRes.ok) {
      const list = await releaseRes.json();
      if (Array.isArray(list) && list.length) release = list[0];
    }

    const data = {
      stars: repo ? repo.stargazers_count : null,
      license: repo && repo.license ? repo.license.spdx_id : 'MIT',
      release: release ? {
        tag: release.tag_name,
        name: release.name,
        published: release.published_at,
        assets: (release.assets || []).map(a => ({ name: a.name, url: a.browser_download_url, size: a.size })),
      } : null,
      releaseUrl: release ? release.html_url : GH_RELEASES,
    };
    writeCache(data);
    return data;
  }

  function dlLink(text, subtitle, url, isPrimary) {
    return '<a class="dl-link" href="' + url + '" target="_blank" rel="noopener">' +
      '<span>' + (isPrimary ? '<strong>' + text + '</strong>' : text) +
      (subtitle ? ' <span class="ext">' + subtitle + '</span>' : '') + '</span>' +
      downloadIcon() + '</a>';
  }

  function renderRelease(data) {
    const r = data.release;
    const prettyTag = r ? r.tag.replace(/^v/, '') : '—';

    // 版本 / star / license 占位
    $('[data-releases]').forEach(n => n.textContent = r ? prettyTag : '—');
    $('[data-stars]').forEach(n => n.textContent = data.stars != null ? data.stars : '—');
    if (data.license) $('[data-license]').forEach(n => n.textContent = data.license);

    if (!r) {
      // 兜底：GitHub API 失败/超时时的静态链接
      el('#dl-win').innerHTML = dlLink('前往 GitHub Releases 下载 Windows 版', '', GH_RELEASES, true);
      el('#dl-mac').innerHTML = dlLink('前往 GitHub Releases 下载 macOS 版', '', GH_RELEASES, true);
      return;
    }

    const base = 'https://github.com/' + REPO + '/releases/download/' + r.tag + '/';
    const byPattern = (re) => {
      const hit = r.assets.find(a => re.test(a.name) && !a.name.includes('blockmap'));
      return hit;
    };

    const winExe = byPattern(/cloud-win-x64\.exe$/i) || byPattern(/win-x64\.exe$/i);
    const winZip = byPattern(/cloud-win-x64\.zip$/i) || byPattern(/win-x64\.zip$/i);
    const macArm = byPattern(/cloud-mac-arm64\.dmg$/i) || byPattern(/mac-arm64\.dmg$/i);
    const macArmZip = byPattern(/cloud-mac-arm64\.zip$/i) || byPattern(/mac-arm64\.zip$/i);
    const macX64 = byPattern(/cloud-mac-x64\.dmg$/i) || byPattern(/mac-x64\.dmg$/i);
    const macX64Zip = byPattern(/cloud-mac-x64\.zip$/i) || byPattern(/mac-x64\.zip$/i);

    const mk = (a) => a ? base + a.name : null;

    let win = '';
    win += dlLink('Windows 安装版', '.exe', mk(winExe) || GH_RELEASES, true);
    win += dlLink('Windows 便携版', '.zip', mk(winZip) || GH_RELEASES, false);

    let mac = '';
    mac += dlLink('Apple 芯片 (M系列)', 'arm64 · dmg', mk(macArm) || GH_RELEASES, true);
    mac += dlLink('Intel 芯片', 'x64 · dmg', mk(macX64) || GH_RELEASES, false);
    mac += dlLink('通用便携包', 'zip', mk(macArmZip) || GH_RELEASES, false);

    el('#dl-win').innerHTML = win;
    el('#dl-mac').innerHTML = mac;
  }

  /* ---------------- 2) 画廊 ---------------- */
  const SHOTS = [
    { file: 'shot-1.png', tab: '首页', label: '首页 · 今日待复习' },
    { file: 'shot-2.png', tab: '知识图谱', label: '学习大纲 · 分层知识图谱' },
    { file: 'shot-3.png', tab: '先教后考', label: '先教后考 · 子知识点清单' },
    { file: 'shot-4.png', tab: '先教后考', label: '先教后考 · 讲解页' },
    { file: 'shot-5.png', tab: '整理', label: '随手记 · Markdown 编辑器' },
    { file: 'shot-6.png', tab: '设置', label: '设置 · 外观与模型' },
    { file: 'shot-7.png', tab: '练习', label: '练习 · 会话作答 + 代码高亮' },
    { file: 'shot-8.png', tab: '模拟面试', label: '模拟面试 · INTERVIEW 配置' },
    { file: 'shot-9.png', tab: '首页', label: '登录页 · 品牌主张' },
  ];

  function renderGallery(filter) {
    const grid = el('#gallery-grid');
    const items = filter ? SHOTS.filter(s => s.tab === filter) : SHOTS;
    grid.innerHTML = items.map((s, i) =>
      '<div class="shot" data-full="assets/screenshots/' + s.file + '" data-cap="' + s.label + '">' +
        '<img src="assets/screenshots/' + s.file + '" alt="' + s.label + '" loading="lazy" />' +
        '<div class="cap">' + s.label + '</div>' +
      '</div>'
    ).join('');
  }

  function renderTabs() {
    const tabs = el('#gallery-tabs');
    const names = ['全部', ...Array.from(new Set(SHOTS.map(s => s.tab)))];
    tabs.innerHTML = names.map((n, i) =>
      '<button class="gtab' + (i === 0 ? ' active' : '') + '" data-filter="' + (n === '全部' ? '' : n) + '">' + n + '</button>'
    ).join('');
    $('.gtab', tabs).forEach(btn => btn.addEventListener('click', () => {
      $('.gtab', tabs).forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      renderGallery(btn.dataset.filter);
    }));
  }

  function initLightbox() {
    const lb = el('#lightbox');
    const img = el('#lightbox-img');
    const cap = el('#lightbox-cap');
    document.addEventListener('click', (e) => {
      const shot = e.target.closest('.shot');
      if (shot) {
        img.src = shot.dataset.full;
        img.alt = shot.dataset.cap;
        cap.textContent = shot.dataset.cap;
        lb.classList.add('show');
      } else if (e.target === lb || e.target.closest('#lightbox')) {
        lb.classList.remove('show');
      }
    });
    document.addEventListener('keydown', (e) => { if (e.key === 'Escape') lb.classList.remove('show'); });
  }

  /* ---------------- 3) 杂项 ---------------- */
  function initMisc() {
    if (el('#year')) el('#year').textContent = new Date().getFullYear();

    const totop = el('#totop');
    window.addEventListener('scroll', () => {
      totop.classList.toggle('show', window.scrollY > 600);
    }, { passive: true });
    totop.addEventListener('click', () => window.scrollTo({ top: 0, behavior: 'smooth' }));
  }

  /* ---------------- Bootstrap ---------------- */
  function boot() {
    renderTabs();
    renderGallery('');
    initLightbox();
    initMisc();

    // 下载区初始加载态
    if (el('#dl-win')) el('#dl-win').innerHTML = '<span class="loading-chip"><span class="spinner"></span>读取最新版本…</span>';
    if (el('#dl-mac')) el('#dl-mac').innerHTML = '<span class="loading-chip"><span class="spinner"></span>读取最新版本…</span>';

    fetchGitHub().then(renderRelease).catch(() => renderRelease({ stars: null, license: 'MIT', release: null }));
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();
})();
