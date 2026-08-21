// 桌面壳主进程：拉起本地 Spring Boot 后端 → 探活闸门 → 加载 React SPA → 退出清理。
// 打包版自包含：内嵌 jlink 精简 JRE + Spring Boot fat jar（electron-builder extraResources
// 塞进 Resources/runtime），无需用户安装 Java / Docker / Gradle；源码运行则走 start.sh。

const { app, BrowserWindow, dialog, ipcMain, safeStorage, nativeImage, Menu, Tray, shell } = require('electron');
const path = require('path');
const { spawn, spawnSync } = require('child_process');
const http = require('http');
const https = require('https');
const net = require('net');
const fs = require('fs');
const { URL } = require('url');
const { autoUpdater } = require('electron-updater');

// Windows 用固定 AppUserModelId 绑定任务栏分组和安装后的 exe 图标，避免回退为 Electron 默认图标。
if (process.platform === 'win32') app.setAppUserModelId('com.mianba.desktop');

// 使用系统代理访问 GitHub 更新服务，仅让本地 Spring Boot 请求绕过代理。
// 不可使用 no-proxy-server：国内网络常依赖系统代理访问 GitHub Release。
app.commandLine.appendSwitch('proxy-bypass-list', 'localhost;127.0.0.1;[::1]');
// 桌面应用不需要浏览器式的 File / View / Window 菜单，但要保留最小「编辑」菜单——
// 否则 Menu.setApplicationMenu(null) 会把 Cmd/Ctrl+C 复制、粘贴、全选等快捷键一起干掉，
// 导致正文文字无法复制。Windows/Linux 上通过 autoHideMenuBar 隐藏菜单栏，不影响快捷键。
Menu.setApplicationMenu(
  Menu.buildFromTemplate([
    ...(process.platform === 'darwin' ? [{ role: 'appMenu' }] : []),
    {
      label: '编辑',
      submenu: [
        { role: 'undo' },
        { role: 'redo' },
        { type: 'separator' },
        { role: 'cut' },
        { role: 'copy' },
        { role: 'paste' },
        { role: 'selectAll' },
      ],
    },
  ])
);

const REPO_ROOT = path.resolve(__dirname, '..', '..'); // interview-desktop/src -> interview/
const BACKEND_PORT = 23333; // 非主流端口，避开 8080 等常用端口被占导致的冲突
const HEALTH_TIMEOUT_MS = 90_000;

// 桌面端图标（与 electron-builder 打包用的 build/icon.png 同源，运行时窗口/启动封面使用）
const APP_ICON = path.join(__dirname, 'icon.png');
// 手动更新跳转地址（与 electron-builder.yml 的 publish.owner/repo 保持一致）
const UPDATE_RELEASES_URL = 'https://github.com/LuckPony/interview/releases';
// GitHub Release 资产下载根（latest-mac.yml 里的相对文件名拼到它后面，得到 dmg 等文件的绝对地址）
const GITHUB_DOWNLOAD_BASE = 'https://github.com/LuckPony/interview/releases/download';

let backend = null;
let mainWindow = null;
let tray = null;
let isQuitting = false;
let manualUpdateInFlight = false; // 手动「检查更新」进行中：由设置页内联展示，不再弹窗
let lastUpdateInfo = null;         // 完整 UpdateInfo（含 files/version）；下载完成后含 downloadedFile
let lastDownloadedFile = null;     // macOS：手动下载的 dmg 绝对路径，供「立即更新」打开

// 单实例锁：同一台机器只允许一个实例。用户重复双击快捷方式时，second-instance 会唤醒已有窗口，
// 而不是再起一个实例——否则多个实例各自拉起后端、抢同一端口（端口被占的根因之一）。
if (!app.requestSingleInstanceLock()) {
  app.exit(0); // 已有实例在运行，立即退出
}
app.on('second-instance', () => showMainWindow());

// 启动封面（后端就绪前显示的加载页）：冷调现代蓝 + 面霸式幽默。
// 说明：Spring Boot = 咖啡品牌梗，配 ☕ 让等待不那么无聊。
const SPLASH_HTML = `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<style>
  * { margin: 0; box-sizing: border-box; }
  body {
    height: 100vh;
    display: grid;
    place-items: center;
    font-family: -apple-system, BlinkMacSystemFont, 'PingFang SC', 'Microsoft YaHei', sans-serif;
    background: linear-gradient(160deg, #F6F8FB 0%, #E8EEF9 100%);
    color: #232B36;
  }
  .card {
    text-align: center;
    background: #fff;
    border: 1px solid #E3E8EF;
    border-radius: 18px;
    padding: 48px 60px;
    box-shadow: 0 24px 60px -24px rgba(35, 43, 54, 0.25);
    animation: rise 0.5s ease-out;
  }
  @keyframes rise { from { opacity: 0; transform: translateY(10px); } to { opacity: 1; transform: translateY(0); } }
  .boss { font-size: 76px; line-height: 1; display: inline-block; animation: bounce 1.3s infinite ease-in-out; }
  @keyframes bounce { 0%, 100% { transform: translateY(0); } 50% { transform: translateY(-12px); } }
  h1 { font-size: 26px; font-weight: 600; margin: 22px 0 10px; letter-spacing: 0.02em; }
  .sub { color: #5B6675; font-size: 15px; }
  .spinner {
    width: 22px; height: 22px; margin: 26px auto 0;
    border: 3px solid #E3E8EF; border-top-color: #3B6FE0;
    border-radius: 50%;
    animation: spin 0.9s linear infinite;
  }
  @keyframes spin { to { transform: rotate(360deg); } }
  .row { margin-top: 26px; font-size: 24px; letter-spacing: 12px; }
  .row span { display: inline-block; animation: bounce 1.6s infinite ease-in-out; }
  .row span:nth-child(2) { animation-delay: 0.2s; }
  .row span:nth-child(3) { animation-delay: 0.4s; }
  .boss img { width: 88px; height: 88px; border-radius: 20px; display: block; box-shadow: 0 8px 24px -12px rgba(35, 43, 54, 0.35); }
  small { display: block; margin-top: 20px; color: #8B95A5; font-size: 12.5px; }
</style>
</head>
<body>
  <div class="card">
    <span class="boss">__LOGO_OR_EMOJI__</span>
    <h1>面霸 · 启动中</h1>
    <p class="sub">面试霸主正在热身，本地服务马上就好…</p>
    <div class="spinner" role="status"></div>
    <div class="row"><span>💼</span><span>🎯</span><span>☕</span></div>
    <small>Spring Boot 在泡咖啡，首次约需十几秒 —— 稍安勿躁</small>
  </div>
</body>
</html>`;

// —— 云/本地双模式 ——
// 打包时写入 config.json（见 sync-spa:cloud 脚本）。serverUrl 非空且非 localhost → 云模式：
// 不拉本地后端，直接加载 SPA（VITE_API_BASE 已在构建时烘焙为服务器地址）。
function loadConfig() {
  try {
    return JSON.parse(fs.readFileSync(path.join(app.getAppPath(), 'config.json'), 'utf8'));
  } catch {
    return {};
  }
}
function isCloud(cfg) {
  const url = (cfg.serverUrl || '').trim();
  return url && !/^https?:\/\/(127\.0\.0\.1|localhost)/i.test(url);
}

function spawnBackend() {
  // detached + 进程组，退出时才能连 JVM 一起杀掉（光 kill 直接子进程杀不掉 gradle→JVM）
  // 后端日志（含异常堆栈）重定向到 backend.log，否则 stdio:'ignore' 会把 500 堆栈吞掉，难以排查
  const logPath = app.isPackaged
    ? path.join(app.getPath('userData'), 'backend.log')
    : path.join(REPO_ROOT, 'interview-desktop', 'backend.log');
  const logFd = fs.openSync(logPath, 'a');
  const env = {
    ...process.env,
    // 复活 start.sh 里的去代理环境变量，确保后端只连本机
    JAVA_TOOL_OPTIONS:
      '-Djava.net.useSystemProxies=false -DsocksProxyHost= -Dhttp.nonProxyHosts=localhost|127.0.0.1|*.local',
  };

  if (app.isPackaged) {
    // 打包版：拉起内嵌精简 JRE + fat jar（electron-builder extraResources 塞进 Resources/runtime）
    // cwd 指向 userData：H2 数据库（./data/interview）与本地文件（./data/files）都落用户数据目录
    // —— .app 包内只读，绝不能往里写。
    const javaBin = path.join(
      process.resourcesPath,
      'runtime',
      'jre',
      'bin',
      process.platform === 'win32' ? 'java.exe' : 'java'
    );
    const jarPath = path.join(process.resourcesPath, 'runtime', 'app.jar');
    backend = spawn(javaBin, ['-jar', jarPath], {
      cwd: app.getPath('userData'),
      detached: true,
      stdio: ['ignore', logFd, logFd],
      env,
    });
  } else {
    // 源码运行（npm start）：走 start.sh（gradle bootRun，开发用）
    backend = spawn('bash', ['start.sh'], {
      cwd: REPO_ROOT,
      detached: true,
      stdio: ['ignore', logFd, logFd],
      env,
    });
  }
  backend.unref();
}

function killBackend() {
  if (!backend || !backend.pid) return;

  const pid = backend.pid;
  backend = null; // 防止 before-quit 和 exit 重复清理同一个 PID
  try {
    if (process.platform === 'win32') {
      // Windows 不支持 Unix 的负 PID 进程组信号；taskkill /T 会连同 Java 子进程树一起结束。
      spawnSync('taskkill.exe', ['/PID', String(pid), '/T', '/F'], {
        windowsHide: true,
        stdio: 'ignore',
      });
    } else {
      // detached 子进程是新进程组组长，负 PID 可终止整个进程组。
      process.kill(-pid, 'SIGTERM');
    }
  } catch {
    // 后端可能已经自行退出。
  }
}

// —— 端口占用预检：启动前先探测，被占就明确提示用户，而不是干等 90 秒再报「启动失败」 ——
function isPortListening(port) {
  return new Promise((resolve) => {
    const sock = net.connect({ host: '127.0.0.1', port, timeout: 600 }, () => {
      sock.destroy();
      resolve(true);
    });
    sock.on('error', () => resolve(false));
    sock.on('timeout', () => { sock.destroy(); resolve(false); });
  });
}

function isOwnBackendOnPort(port) {
  return new Promise((resolve) => {
    const req = http.get({ host: '127.0.0.1', port, path: '/actuator/health', timeout: 800 }, (res) => {
      let body = '';
      res.on('data', (c) => (body += c));
      res.on('end', () => {
        // 只要返回 Spring Boot health JSON（status=UP 或 DOWN）就认定是我们自己的后端；
        // 其它程序占用该端口不会返回这种 JSON。邮件等软指标 DOWN 不影响复用判断。
        resolve(/"status"\s*:\s*"(UP|DOWN)"/.test(body));
      });
    });
    req.on('error', () => resolve(false));
    req.on('timeout', () => { req.destroy(); resolve(false); });
  });
}

// 返回值：true=端口空闲（需要自己拉起后端）；'reuse'=已有我们自己的后端在跑（直接复用）；false=用户选择退出
async function ensureBackendPortFree() {
  for (;;) {
    if (!(await isPortListening(BACKEND_PORT))) return true;      // 空闲
    if (await isOwnBackendOnPort(BACKEND_PORT)) return 'reuse';   // 我们自己残留的后端，直接复用
    const { response } = await dialog.showMessageBox({
      type: 'warning',
      title: '端口被占用',
      message: `本地服务端口 ${BACKEND_PORT} 被其他程序占用`,
      detail: `面霸需要该端口启动内置服务，请关闭占用该端口的程序后点「重试」。\n\n查看占用进程：命令行执行\nnetstat -ano | findstr :${BACKEND_PORT}`,
      buttons: ['重试', '退出'],
      defaultId: 0,
      cancelId: 1,
    });
    if (response !== 0) return false; // 用户选「退出」
    // 选「重试」→ 循环再探测
  }
}

// 探活闸门：必须命中 /actuator/health 且返回 {"status":"UP"} 才算后端就绪。
// 这样端口被其它程序占用时（返回 404/别的），不会误判为"后端已起"，而是明确报"端口被占"。
function waitForBackend(timeoutMs) {
  return new Promise((resolve, reject) => {
    const deadline = Date.now() + timeoutMs;
    const attempt = () => {
      const req = http.get(
        { host: '127.0.0.1', port: BACKEND_PORT, path: '/actuator/health', timeout: 800 },
        (res) => {
          let body = '';
          res.on('data', (c) => (body += c));
          res.on('end', () => {
            // Spring Boot health JSON（UP 或 DOWN 都算已就绪：邮件等软指标 DOWN 不影响使用）
            if (/"status"\s*:\s*"(UP|DOWN)"/.test(body)) resolve();
            else if (Date.now() > deadline)
              reject(new Error(`后端未就绪：端口 ${BACKEND_PORT} 被占用或后端启动失败`));
            else setTimeout(attempt, 1000);
          });
        }
      );
      req.on('error', () => {
        if (Date.now() > deadline) reject(new Error(`后端启动超时：端口 ${BACKEND_PORT} 无响应`));
        else setTimeout(attempt, 1000);
      });
      req.on('timeout', () => req.destroy());
    };
    attempt();
  });
}

// —— 自动更新：所有打包版启用 ——
// 更新源由 electron-builder 的 GitHub publish 配置写入 app-update.yml。
// 流程分两步：检查（只查不下载）→ 下载（按平台下载正确格式）→ 立即更新。
// - Windows/Linux：electron-updater 下载 NSIS 安装包 / AppImage，quitAndInstall 一键安装。
// - macOS（CI 未签名）：手动下载 GitHub Release 里的 .dmg（和手动装是同一个文件），
//   下载完成后打开 dmg，用户把新 app 拖进「应用程序」覆盖。
// 更新状态通过 update:status 事件推给渲染进程（设置页内联展示进度与按钮）；
// 启动时的自动检查只弹「发现新版本」提醒，不自动下载。
function broadcastUpdate(payload) {
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send('update:status', payload);
  }
}

// 更新日志写盘：排查下载/安装问题时看 userData/updater.log（源码运行不写）
function updateLog(level, message) {
  if (!app.isPackaged) return;
  const text = message instanceof Error ? message.stack || message.message : String(message);
  try {
    fs.appendFileSync(
      path.join(app.getPath('userData'), 'updater.log'),
      `${new Date().toISOString()} [${level}] ${text}\n`,
    );
  } catch {
    // 日志失败不能影响应用启动。
  }
}

function installDownloadedUpdate(info) {
  if (process.platform === 'darwin') {
    // macOS 未签名：挂载并打开 dmg，用户在 Finder 里把新 app 拖进「应用程序」覆盖
    const dmg = lastDownloadedFile || (info && info.downloadedFile);
    if (dmg) {
      shell.openPath(dmg).then((err) => {
        if (err) updateLog('ERROR', `打开 dmg 失败：${err}`);
      });
    } else {
      shell.openExternal(`${UPDATE_RELEASES_URL}/latest`);
    }
    return;
  }
  // Windows/Linux：更新前显式进入「退出」状态 + 先杀本地后端，否则 NSIS 安装器会判“应用无法关闭”。
  isQuitting = true;
  killBackend();
  autoUpdater.quitAndInstall();
  // 兜底：几秒后仍未退干净就强制退出，确保安装器能继续（不会卡在“请手动关闭”）。
  setTimeout(() => app.exit(0), 3000);
}

function showUpdateDownloadedDialog(info) {
  const isMac = process.platform === 'darwin';
  dialog
    .showMessageBox({
      type: 'info',
      title: '更新已就绪',
      message: `新版本 v${info.version} 已下载完成`,
      detail: isMac
        ? '点击「立即更新」打开 dmg 安装包，把新版本拖进「应用程序」覆盖即可。'
        : '点击「立即更新」重启并完成安装。',
      buttons: ['立即更新', '稍后再说'],
      defaultId: 0,
      cancelId: 1,
    })
    .then(({ response }) => {
      if (response === 0) installDownloadedUpdate(info);
    });
}

// GitHub Release 里 mac 的 .dmg 文件（与 zip 并列；electron-updater 默认只下 zip，这里手动下 dmg）
function findDmgFile(info) {
  const files = (info && Array.isArray(info.files) && info.files) || [];
  return (
    files.find((f) => f && f.url && f.url.toLowerCase().endsWith('.dmg')) ||
    files.find((f) => f && String(f.type).toLowerCase() === 'dmg')
  );
}

// latest-mac.yml 里的 url 是相对文件名，拼成 GitHub Release 的绝对下载地址
function assetUrl(info, file) {
  const u = (file && file.url) || '';
  if (/^https?:\/\//i.test(u)) return u;
  return `${GITHUB_DOWNLOAD_BASE}/v${info.version}/${u.replace(/^\//, '')}`;
}

// 用 Node https 下载 GitHub Release 资产（自动跟随 302 重定向，走系统代理），边下边回报进度。
function downloadFile(url, destPath, onProgress) {
  return new Promise((resolve, reject) => {
    const doRequest = (currentUrl, redirects) => {
      if (redirects > 5) {
        reject(new Error('下载重定向次数过多'));
        return;
      }
      let parsed;
      try {
        parsed = new URL(currentUrl);
      } catch (e) {
        reject(e);
        return;
      }
      const req = https.get(
        parsed,
        { headers: { 'User-Agent': `mianba-desktop/${app.getVersion()}` } },
        (res) => {
          const status = res.statusCode || 0;
          if (status >= 300 && status < 400 && res.headers.location) {
            res.resume();
            doRequest(new URL(res.headers.location, parsed).toString(), redirects + 1);
            return;
          }
          if (status !== 200) {
            res.resume();
            reject(new Error(`下载失败 HTTP ${status}`));
            return;
          }
          const total = Number(res.headers['content-length'] || 0);
          let received = 0;
          const out = fs.createWriteStream(destPath);
          out.on('error', (e) => {
            res.destroy();
            reject(e);
          });
          res.on('data', (chunk) => {
            received += chunk.length;
            if (onProgress && total > 0) onProgress(Math.round((received / total) * 100));
          });
          res.on('end', () => out.end(() => resolve(destPath)));
          res.on('error', (e) => {
            out.destroy();
            reject(e);
          });
          res.pipe(out);
        },
      );
      req.on('error', reject);
    };
    doRequest(url, 0);
  });
}

// macOS：下载对应架构的 dmg 到「下载」目录，完成后广播 downloaded。
async function downloadMacDmg(info) {
  const dmg = findDmgFile(info);
  if (!dmg) throw new Error('未在更新信息里找到 dmg 下载地址');
  const filename = path.basename(dmg.url);
  const destPath = path.join(app.getPath('downloads'), filename);
  updateLog('INFO', `开始下载 dmg：${assetUrl(info, dmg)} -> ${destPath}`);
  await downloadFile(assetUrl(info, dmg), destPath, (percent) => {
    broadcastUpdate({ phase: 'downloading', percent });
  });
  lastDownloadedFile = destPath;
  updateLog('INFO', `dmg 下载完成：${destPath}`);
  manualUpdateInFlight = false; // 手动流程走完（已下载），复位
  broadcastUpdate({ phase: 'downloaded', version: info.version });
}

function setupAutoUpdate() {
  if (!app.isPackaged) return; // 源码运行（npm start）不检查更新

  autoUpdater.logger = {
    info: (message) => updateLog('INFO', message),
    warn: (message) => updateLog('WARN', message),
    error: (message) => updateLog('ERROR', message),
    debug: (message) => updateLog('DEBUG', message),
  };
  // 只检查不下载：下载由设置页「下载更新」显式触发（Windows/Linux 走 electron-updater，mac 手动下 dmg）
  autoUpdater.autoDownload = false;
  // 安装只由显式操作触发（设置页/弹窗点「立即更新」→ quitAndInstall）；
  // 关掉 onAppQuit 自动装，避免 macOS 未签名在退出时尝试安装而失败/报错。
  autoUpdater.autoInstallOnAppQuit = false;

  autoUpdater.on('checking-for-update', () => {
    broadcastUpdate({ phase: 'checking' });
  });

  autoUpdater.on('update-available', (info) => {
    lastUpdateInfo = info; // 完整 UpdateInfo（含 files），供下载/安装使用
    broadcastUpdate({ phase: 'available', version: info.version });
    updateLog('INFO', `发现新版本 v${info.version}`);
    if (manualUpdateInFlight) return; // 手动检查：设置页内联展示，不弹窗
    dialog.showMessageBox({
      type: 'info',
      title: '发现新版本',
      message: `发现新版本 v${info.version}`,
      detail: '去「设置 → 检查更新」下载并更新。',
      buttons: ['知道了'],
    });
  });

  autoUpdater.on('update-not-available', (info) => {
    broadcastUpdate({ phase: 'not-available', version: info.version });
    manualUpdateInFlight = false;
    updateLog('INFO', `当前已是最新版本 v${info.version}`);
  });

  autoUpdater.on('error', (error) => {
    broadcastUpdate({ phase: 'error', message: error && error.message ? error.message : String(error) });
    manualUpdateInFlight = false;
    updateLog('ERROR', error);
  });

  autoUpdater.on('download-progress', (progress) => {
    broadcastUpdate({ phase: 'downloading', percent: Math.round(progress.percent) });
    if (tray) tray.setToolTip(`面霸 · 正在下载更新 ${Math.round(progress.percent)}%`);
  });

  autoUpdater.on('update-downloaded', (info) => {
    lastUpdateInfo = info;
    const wasManual = manualUpdateInFlight;
    manualUpdateInFlight = false;
    broadcastUpdate({ phase: 'downloaded', version: info.version });
    if (tray) tray.setToolTip('面霸 · 备考助手');
    if (wasManual) return; // 手动检查：设置页内联展示，不弹窗
    showUpdateDownloadedDialog(info);
  });

  // 启动后检查（只查不下载）；失败写入 updater.log，不再静默吞掉。
  setTimeout(() => {
    autoUpdater.checkForUpdates().catch((error) => updateLog('ERROR', error));
  }, 8000); // 等窗口起来再查，避免拖慢首屏
}

function showMainWindow() {
  if (!mainWindow || mainWindow.isDestroyed()) {
    mainWindow = createWindow();
  }
  if (mainWindow.isMinimized()) mainWindow.restore();
  mainWindow.show();
  mainWindow.focus();
}

// macOS 菜单栏（托盘）图标必须是 16~22pt 的小图；直接塞 1024 大图标会把整个菜单栏撑爆。
// 按平台缩到合适尺寸；macOS 额外补 @2x 表示，Retina 屏下不模糊。
function trayIcon() {
  const base = nativeImage.createFromPath(APP_ICON);
  if (base.isEmpty()) return base;
  const size = process.platform === 'darwin' ? 18 : 32;
  const img = base.resize({ width: size, height: size, quality: 'best' });
  if (process.platform === 'darwin') {
    const img2x = base.resize({ width: size * 2, height: size * 2, quality: 'best' });
    img.addRepresentation({ scaleFactor: 2, width: size * 2, height: size * 2, buffer: img2x.toPNG() });
  }
  return img;
}

function createTray() {
  if (tray) return;
  tray = new Tray(trayIcon());
  tray.setToolTip('面霸 · 备考助手');
  tray.setContextMenu(
    Menu.buildFromTemplate([
      { label: '打开面霸', click: showMainWindow },
      { type: 'separator' },
      {
        label: '彻底退出',
        click: () => {
          isQuitting = true;
          app.quit();
        },
      },
    ])
  );
  tray.on('double-click', showMainWindow);
}

function createWindow() {
  const win = new BrowserWindow({
    width: 1200,
    height: 840,
    autoHideMenuBar: true, // Windows/Linux 隐藏菜单栏（Alt 唤出）；菜单仅为保留复制/粘贴/全选快捷键
    backgroundColor: '#F6F8FB',
    icon: APP_ICON, // Windows/Linux 的窗口/任务栏图标；macOS 用打包进 .app 的图标
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
    },
  });

  const spaPath = path.join(app.getAppPath(), 'app-dist', 'index.html');
  const loadSpa = () => {
    if (fs.existsSync(spaPath)) win.loadFile(spaPath);
    else win.loadURL('http://localhost:5173'); // 没构建 SPA 时回退 dev server
  };

  const cfg = loadConfig();
  if (isCloud(cfg)) {
    // 云模式：不拉本地后端、不显示启动封面，直接加载 SPA（API 直连云端）
    loadSpa();
  } else {
    // 本地模式：先显示启动封面，等本地后端就绪后再换 SPA
    // 注意：data: URL 必须声明 charset=utf-8，否则 Chromium 默认按 Latin-1 解码中文会乱码
    // 启动封面右上角换成产品 logo（读不到就用原来的 😎 兜底）
    const logoImg = nativeImage.createFromPath(APP_ICON);
    const logoHtml = logoImg.isEmpty()
      ? '😎'
      : `<img src="${logoImg.resize({ width: 128, height: 128 }).toDataURL()}" alt="面霸">`;
    const splash = SPLASH_HTML.replace('__LOGO_OR_EMOJI__', logoHtml);
    win.loadURL('data:text/html;charset=utf-8,' + encodeURIComponent(splash));
    waitForBackend(HEALTH_TIMEOUT_MS)
      .then(loadSpa)
      .catch((e) => {
        dialog.showErrorBox('启动失败', e.message);
        app.quit();
      });
  }

  // 关闭窗口时保留托盘、定时任务和本地后端；只有托盘“彻底退出”或系统退出才真正结束。
  win.on('close', (event) => {
    if (!isQuitting) {
      event.preventDefault();
      win.hide();
    }
  });
  win.on('closed', () => {
    if (mainWindow === win) mainWindow = null;
  });

  return win;
}

// —— 本地 LLM key 桥：用户的 AI key 只存本机（Electron userData），不落服务器 ——
// 渲染进程每次请求时读出并带 X-LLM-Key 头，后端「只用不存」。
// 系统支持（macOS Keychain / Windows DPAPI）时用 safeStorage 加密落盘，否则明文。
const LLM_KEY_FILE = () => path.join(app.getPath('userData'), 'llm-key.json');
function readLlmKey() {
  try {
    const raw = JSON.parse(fs.readFileSync(LLM_KEY_FILE(), 'utf8'));
    if (!raw.key) return '';
    if (raw.enc && safeStorage.isEncryptionAvailable()) {
      return safeStorage.decryptString(Buffer.from(raw.key, 'base64'));
    }
    return raw.key;
  } catch {
    return '';
  }
}
function writeLlmKey(key) {
  const k = key || '';
  try {
    if (k && safeStorage.isEncryptionAvailable()) {
      fs.writeFileSync(LLM_KEY_FILE(), JSON.stringify({ enc: true, key: safeStorage.encryptString(k).toString('base64') }));
    } else {
      fs.writeFileSync(LLM_KEY_FILE(), JSON.stringify({ enc: false, key: k }));
    }
  } catch (e) {
    console.error('保存 LLM key 失败:', e.message);
  }
  return true;
}
ipcMain.handle('llm:getKey', () => readLlmKey());
ipcMain.handle('llm:setKey', (_e, key) => writeLlmKey(key));

// —— 本地 fs 桥：渲染进程调 window.electronAPI.pickFile / pickFolder ——
ipcMain.handle('dialog:pickFile', async () => {
  const { canceled, filePaths } = await dialog.showOpenDialog({
    properties: ['openFile'],
    filters: [{ name: '资料', extensions: ['pdf', 'txt', 'md', 'markdown', 'mdx', 'docx'] }],
  });
  return canceled || filePaths.length === 0 ? null : filePaths[0];
});

ipcMain.handle('dialog:pickFolder', async () => {
  const { canceled, filePaths } = await dialog.showOpenDialog({ properties: ['openDirectory'] });
  return canceled || filePaths.length === 0 ? null : filePaths[0];
});

// —— 云模式读盘桥：后端在服务器上读不到用户本机路径 ——
// Electron 在自己机器上遍历文件夹，把支持的文件读成字节（base64）交给服务器 Tika 解析合并。
// 常量与后端 CorpusService 保持同步（SYSTEM_ROOTS / SKIP_DIRS / SUPPORTED_EXT / MAX_FILE_BYTES）。
const PATH_SUPPORTED_EXT = new Set(['pdf', 'txt', 'md', 'markdown', 'mdx', 'docx']);
const PATH_SKIP_DIRS = new Set([
  '.git', 'node_modules', 'target', 'build', 'dist', 'out',
  '.next', 'coverage', '.idea', '.vscode', '__pycache__',
]);
const PATH_SYSTEM_ROOTS = [
  '/System', '/usr', '/bin', '/sbin', '/etc',
  '/private/var', '/private/etc', '/Library', '/Applications',
  'C:\\Windows', 'C:\\Program Files', 'C:\\ProgramData',
];
const PATH_MAX_FILE_BYTES = 20 * 1024 * 1024;
const PATH_MAX_TOTAL_BYTES = 40 * 1024 * 1024; // 服务器 multipart 上限 50MB，留表单开销余量

async function collectPathFiles(root) {
  const out = [];
  let total = 0;
  const walk = async (dir) => {
    const entries = await fs.promises.readdir(dir, { withFileTypes: true });
    for (const e of entries) {
      const full = path.join(dir, e.name);
      if (e.isDirectory()) {
        if (!PATH_SKIP_DIRS.has(e.name)) await walk(full);
      } else if (e.isFile()) {
        const ext = path.extname(e.name).slice(1).toLowerCase();
        if (!PATH_SUPPORTED_EXT.has(ext)) continue;
        const st = await fs.promises.stat(full);
        if (st.size > PATH_MAX_FILE_BYTES) continue;
        if (total + st.size > PATH_MAX_TOTAL_BYTES) {
          throw new Error(
            `所选内容总大小超过 40MB，超出服务器上传上限。请精简文件夹，或改用本地模式（后端在本机直接读盘）。`
          );
        }
        const buf = await fs.promises.readFile(full);
        out.push({ name: e.name, data: buf.toString('base64') });
        total += st.size;
      }
    }
  };
  await walk(root);
  return { name: path.basename(root), files: out };
}

ipcMain.handle('fs:collectPath', async (_e, p) => {
  try {
    if (!p || typeof p !== 'string') return { error: '缺少路径' };
    const real = fs.realpathSync(p);
    if (!fs.existsSync(real)) return { error: `路径无法访问或不存在：${p}` };
    for (const sys of PATH_SYSTEM_ROOTS) {
      if (real === sys || real.startsWith(sys + path.sep)) {
        return { error: `出于安全考虑，不能读取系统目录：${real}` };
      }
    }
    const { name, files } = await collectPathFiles(real);
    if (files.length === 0) {
      return { error: '在该路径下没找到可解析的资料（支持 pdf / txt / md / docx）。' };
    }
    return { name, files };
  } catch (e) {
    return { error: (e && e.message) || '读取本地文件失败' };
  }
});

// 云模式标记：渲染进程据此决定「选本地文件夹」是走后端读盘还是本机读盘
ipcMain.handle('app:isCloud', () => isCloud(loadConfig()));

// —— 版本号 + 检查更新桥 ——
ipcMain.handle('app:getVersion', () => app.getVersion());
ipcMain.handle('app:getPlatform', () => process.platform);

ipcMain.handle('app:checkForUpdates', async () => {
  if (!app.isPackaged) return { error: '开发模式不支持检查更新，请使用打包后的应用' };
  manualUpdateInFlight = true;
  try {
    await autoUpdater.checkForUpdates();
  } catch (e) {
    manualUpdateInFlight = false;
    return { error: (e && e.message) || '检查更新失败' };
  }
  // 结果不从这里返回：检查结果通过 update:status 事件推给设置页，
  // manualUpdateInFlight 在 update-not-available / error / update-downloaded 里复位。
  return { ok: true };
});

// 只下载、不安装：Windows/Linux 走 electron-updater（正确格式的安装包），macOS 手动下 dmg。
ipcMain.handle('app:downloadUpdate', async () => {
  if (!app.isPackaged) return { error: '开发模式不支持下载更新，请使用打包后的应用' };
  if (!lastUpdateInfo) return { error: '请先检查更新' };
  if (process.platform === 'darwin') {
    try {
      await downloadMacDmg(lastUpdateInfo);
      return { ok: true };
    } catch (e) {
      updateLog('ERROR', e);
      return { error: (e && e.message) || '下载更新失败' };
    }
  }
  try {
    await autoUpdater.downloadUpdate();
    return { ok: true };
  } catch (e) {
    updateLog('ERROR', e);
    return { error: (e && e.message) || '下载更新失败' };
  }
});

ipcMain.handle('app:installUpdate', () => {
  if (!lastUpdateInfo) return { ok: false, error: '暂无已下载的更新' };
  installDownloadedUpdate(lastUpdateInfo);
  return { ok: true };
});

app.whenReady().then(async () => {
  if (!isCloud(loadConfig())) {
    // 本地模式：端口预检。被其它程序占用就明确提示，而不是干等 90 秒再报「启动失败」。
    const portState = await ensureBackendPortFree();
    if (portState === false) {
      app.exit(1);
      return;
    }
    if (portState === true) {
      spawnBackend(); // 端口空闲才拉起新后端
    }
    // portState === 'reuse'：已有我们自己的后端在跑，直接复用，不再拉新的
  }

  mainWindow = createWindow();
  createTray();
  setupAutoUpdate(); // 本地模式和云模式都检查 GitHub Release 更新；源码运行会自动跳过

  app.on('activate', showMainWindow);
});

// 只有真正退出应用时才清理后端；关闭窗口只隐藏到托盘。
app.on('before-quit', () => {
  isQuitting = true;
  killBackend();
});
process.on('exit', killBackend);
