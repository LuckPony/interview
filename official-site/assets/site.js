(function () {
  'use strict';

  const qs = (selector, root = document) => root.querySelector(selector);
  const qsa = (selector, root = document) => [...root.querySelectorAll(selector)];
  const repo = document.body.dataset.repo || 'LuckPony/interview';
  const repoUrl = `https://github.com/${repo}`;
  const releaseUrl = `${repoUrl}/releases`;

  const shots = [
    { file: 'dashboard-latest.png', tab: '首页', title: '学习总览', subtitle: '真实进度与成长趋势' },
    { file: 'capture-latest.png', tab: '对话沉淀', title: '上下文问答沉淀', subtitle: '逐问答存卡与代码复制' },
    { file: 'knowledge-library-latest.png', tab: '知识库', title: '知识库管理', subtitle: '资料、标签与知识主题' },
    { file: 'knowledge-detail-latest.png', tab: '知识库', title: '资料详情', subtitle: '章节索引、用途与原文件' },
    { file: 'knowledge-tools-latest.png', tab: '知识库', title: '知识库工具库', subtitle: '翻译、提炼与术语解释' },
    { file: 'interview-latest.png', tab: '面试', title: '模拟面试', subtitle: '简历、方向与资料联合出题' },
    { file: 'settings-latest.png', tab: '设置', title: '模型与外观设置', subtitle: 'Provider 与模型快捷选择' },
    { file: 'login-latest.png', tab: '登录', title: '账号登录', subtitle: '为下一份 Offer 做准备' },
  ];

  function initNavigation() {
    const header = qs('#site-nav');
    const toggle = qs('#nav-toggle');
    const links = qs('#nav-links');
    const syncHeader = () => header?.classList.toggle('scrolled', window.scrollY > 16);
    syncHeader();
    window.addEventListener('scroll', syncHeader, { passive: true });

    toggle?.addEventListener('click', () => {
      const open = !links.classList.contains('open');
      links.classList.toggle('open', open);
      toggle.setAttribute('aria-expanded', String(open));
      document.body.classList.toggle('menu-open', open);
    });
    qsa('a', links).forEach((link) => link.addEventListener('click', () => {
      links.classList.remove('open');
      toggle?.setAttribute('aria-expanded', 'false');
      document.body.classList.remove('menu-open');
    }));
  }

  function initReveal() {
    const items = qsa('.reveal');
    if (!('IntersectionObserver' in window)) {
      items.forEach((item) => item.classList.add('visible'));
      return;
    }
    const observer = new IntersectionObserver((entries) => {
      entries.forEach((entry) => {
        if (!entry.isIntersecting) return;
        entry.target.classList.add('visible');
        observer.unobserve(entry.target);
      });
    }, { threshold: 0.1, rootMargin: '0px 0px -30px' });
    items.forEach((item) => observer.observe(item));
  }

  function openLightbox(src, caption) {
    const lightbox = qs('#lightbox');
    const image = qs('#lightbox-image');
    const text = qs('#lightbox-caption');
    if (!lightbox || !image || !text) return;
    image.src = src;
    image.alt = caption;
    text.textContent = caption;
    lightbox.classList.add('open');
    document.body.style.overflow = 'hidden';
    qs('#lightbox-close')?.focus();
  }

  function closeLightbox() {
    qs('#lightbox')?.classList.remove('open');
    document.body.style.overflow = '';
  }

  function renderGallery(filter = '') {
    const grid = qs('#gallery-grid');
    if (!grid) return;
    const visible = filter ? shots.filter((shot) => shot.tab === filter) : shots;
    grid.innerHTML = visible.map((shot) => `
      <figure class="gallery-item" tabindex="0"
        data-lightbox="assets/screenshots/${shot.file}"
        data-caption="${shot.title} · ${shot.subtitle}">
        <img src="assets/screenshots/${shot.file}" alt="${shot.title}：${shot.subtitle}" loading="lazy" />
        <figcaption><span>${shot.title}<br /><small>${shot.subtitle}</small></span><small>VIEW ↗</small></figcaption>
      </figure>
    `).join('');
  }

  function initGallery() {
    const tabs = qs('#gallery-tabs');
    if (!tabs) return;
    const names = ['全部', ...new Set(shots.map((shot) => shot.tab))];
    tabs.innerHTML = names.map((name, index) => `
      <button class="gallery-tab${index === 0 ? ' active' : ''}" type="button" role="tab"
        aria-selected="${index === 0}" data-filter="${name === '全部' ? '' : name}">${name}</button>
    `).join('');
    renderGallery();
    tabs.addEventListener('click', (event) => {
      const button = event.target.closest('.gallery-tab');
      if (!button) return;
      qsa('.gallery-tab', tabs).forEach((tab) => {
        const active = tab === button;
        tab.classList.toggle('active', active);
        tab.setAttribute('aria-selected', String(active));
      });
      renderGallery(button.dataset.filter || '');
    });

    document.addEventListener('click', (event) => {
      const target = event.target.closest('[data-lightbox]');
      if (target) openLightbox(target.dataset.lightbox, target.dataset.caption || '产品截图');
    });
    document.addEventListener('keydown', (event) => {
      if (event.key === 'Escape') closeLightbox();
      if ((event.key === 'Enter' || event.key === ' ') && event.target.matches?.('[data-lightbox]')) {
        event.preventDefault();
        openLightbox(event.target.dataset.lightbox, event.target.dataset.caption || '产品截图');
      }
    });
    qs('#lightbox-close')?.addEventListener('click', closeLightbox);
    qs('#lightbox')?.addEventListener('click', (event) => {
      if (event.target.id === 'lightbox') closeLightbox();
    });
  }

  const cacheKey = 'mianba_release_cache_v2';
  const cacheTtl = 10 * 60 * 1000;

  function readReleaseCache() {
    try {
      const value = JSON.parse(localStorage.getItem(cacheKey));
      return value && Date.now() - value.savedAt < cacheTtl ? value.data : null;
    } catch {
      return null;
    }
  }

  function writeReleaseCache(data) {
    try {
      localStorage.setItem(cacheKey, JSON.stringify({ savedAt: Date.now(), data }));
    } catch {}
  }

  async function fetchReleaseData() {
    const cached = readReleaseCache();
    if (cached) return cached;
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), 7000);
    const options = { headers: { Accept: 'application/vnd.github+json' }, signal: controller.signal };
    try {
      const [repoResponse, releaseResponse] = await Promise.all([
        fetch(`https://api.github.com/repos/${repo}`, options).catch(() => null),
        fetch(`https://api.github.com/repos/${repo}/releases?per_page=1`, options).catch(() => null),
      ]);
      const repoData = repoResponse?.ok ? await repoResponse.json() : null;
      const releases = releaseResponse?.ok ? await releaseResponse.json() : [];
      const latest = Array.isArray(releases) ? releases[0] : null;
      const data = {
        stars: repoData?.stargazers_count ?? null,
        license: repoData?.license?.spdx_id || 'MIT',
        tag: latest?.tag_name || null,
        assets: Array.isArray(latest?.assets)
          ? latest.assets.map((asset) => ({ name: asset.name, url: asset.browser_download_url }))
          : [],
      };
      writeReleaseCache(data);
      return data;
    } finally {
      window.clearTimeout(timeout);
    }
  }

  function downloadIcon() {
    return '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3v12m0 0 4-4m-4 4-4-4M5 21h14"/></svg>';
  }

  function downloadLink(label, detail, url) {
    return `<a class="download-link" href="${url}" target="_blank" rel="noopener"><span>${label}<small> · ${detail}</small></span>${downloadIcon()}</a>`;
  }

  function renderRelease(data) {
    const version = data.tag ? data.tag.replace(/^v/i, '') : '—';
    qsa('[data-releases]').forEach((element) => { element.textContent = version; });
    qsa('[data-stars]').forEach((element) => { element.textContent = data.stars ?? '—'; });
    qsa('[data-license]').forEach((element) => { element.textContent = data.license || 'MIT'; });

    const find = (pattern) => data.assets.find((asset) => pattern.test(asset.name) && !/blockmap/i.test(asset.name));
    const winExe = find(/cloud-win-x64\.exe$/i) || find(/win-x64\.exe$/i);
    const winZip = find(/cloud-win-x64\.zip$/i) || find(/win-x64\.zip$/i);
    const macArm = find(/cloud-mac-arm64\.dmg$/i) || find(/mac-arm64\.dmg$/i);
    const macX64 = find(/cloud-mac-x64\.dmg$/i) || find(/mac-x64\.dmg$/i);
    const win = qs('#dl-win');
    const mac = qs('#dl-mac');
    if (win) {
      win.innerHTML = [
        downloadLink('Windows 安装版', 'exe', winExe?.url || releaseUrl),
        downloadLink('Windows 便携版', 'zip', winZip?.url || releaseUrl),
      ].join('');
    }
    if (mac) {
      mac.innerHTML = [
        downloadLink('Apple 芯片', 'arm64 · dmg', macArm?.url || releaseUrl),
        downloadLink('Intel 芯片', 'x64 · dmg', macX64?.url || releaseUrl),
      ].join('');
    }
  }

  function initMisc() {
    const year = qs('#year');
    if (year) year.textContent = new Date().getFullYear();
    const top = qs('#to-top');
    const syncTop = () => top?.classList.toggle('visible', window.scrollY > 620);
    syncTop();
    window.addEventListener('scroll', syncTop, { passive: true });
    top?.addEventListener('click', () => window.scrollTo({ top: 0, behavior: 'smooth' }));
  }

  function boot() {
    initNavigation();
    initReveal();
    initGallery();
    initMisc();
    fetchReleaseData()
      .then(renderRelease)
      .catch(() => renderRelease({ stars: null, license: 'MIT', tag: null, assets: [] }));
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();
})();
