// 桌面壳主进程：拉起本地 Spring Boot 后端 → 探活闸门 → 加载 React SPA → 退出清理。
// 打包版自包含：内嵌 jlink 精简 JRE + Spring Boot fat jar（electron-builder extraResources
// 塞进 Resources/runtime），无需用户安装 Java / Docker / Gradle；源码运行则走 start.sh。

const { app, BrowserWindow, dialog, ipcMain, safeStorage, nativeImage, Menu } = require('electron');
const path = require('path');
const { spawn } = require('child_process');
const http = require('http');
const fs = require('fs');
const { autoUpdater } = require('electron-updater');

// Windows 用固定 AppUserModelId 绑定任务栏分组和安装后的 exe 图标，避免回退为 Electron 默认图标。
if (process.platform === 'win32') app.setAppUserModelId('com.mianba.desktop');

// 防 Electron 的 Chromium 把 127.0.0.1 拐去代理（同 start.sh 的坑）
app.commandLine.appendSwitch('no-proxy-server');
// 桌面应用不需要浏览器式的 File / Edit / View 菜单；保留原生窗口的右键菜单即可。
// Electron 在未显式设置菜单时会自动生成这些菜单，打包后会出现在页面顶部。
Menu.setApplicationMenu(null);

const REPO_ROOT = path.resolve(__dirname, '..', '..'); // interview-desktop/src -> interview/
const BACKEND_PORT = 8080;
const HEALTH_TIMEOUT_MS = 90_000;

// 桌面端图标（与 electron-builder 打包用的 build/icon.png 同源，运行时窗口/启动封面使用）
const APP_ICON = path.join(__dirname, 'icon.png');

let backend = null;

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
  if (backend && backend.pid) {
    try {
      process.kill(-backend.pid, 'SIGTERM');
    } catch {
      /* 已退出 */
    }
  }
}

// 探活闸门：任何 HTTP 响应（含 401/404）都表示「端口在监听 = 后端已起」；
// 只有 ECONNREFUSED（端口没人听）才说明还没好，继续轮询。
function waitForBackend(timeoutMs) {
  return new Promise((resolve, reject) => {
    const deadline = Date.now() + timeoutMs;
    const attempt = () => {
      const req = http.get(
        { host: '127.0.0.1', port: BACKEND_PORT, path: '/', timeout: 800 },
        (res) => {
          res.resume();
          resolve();
        }
      );
      req.on('error', () => {
        if (Date.now() > deadline) reject(new Error('后端启动超时（请确认 Docker / JDK 已就绪）'));
        else setTimeout(attempt, 1000);
      });
      req.on('timeout', () => req.destroy());
    };
    attempt();
  });
}

// —— 自动更新：仅「云模式 + 打包版」启用 ——
// 更新源在构建时由 electron-builder.yml 的 publish.url 写入 app-update.yml（electron-updater 自动读取）。
// Windows：NSIS 安装包可直接自动更新（未签名也能跑，首次安装会有 SmartScreen 提示）。
// macOS：自动更新需要开发者签名（Apple Developer 证书，$99/年）；未签名时本段自动跳过，只能手动重新下载。
function setupAutoUpdate() {
  if (!app.isPackaged) return; // 源码运行（npm start）不检查更新
  autoUpdater.autoDownload = true;
  autoUpdater.autoInstallOnAppQuit = true;

  autoUpdater.on('update-downloaded', (info) => {
    dialog
      .showMessageBox({
        type: 'info',
        title: '发现新版本',
        message: `新版本 v${info.version} 已下载完成`,
        detail: '重启应用即可完成更新。',
        buttons: ['立即重启', '稍后再说'],
        defaultId: 0,
        cancelId: 1,
      })
      .then(({ response }) => {
        if (response === 0) autoUpdater.quitAndInstall();
      });
  });

  // 静默检查：无更新 / 网络失败都只是日志，不打扰用户
  setTimeout(() => {
    autoUpdater.checkForUpdates().catch(() => {});
  }, 8000); // 等窗口起来再查，避免拖慢首屏
}

function createWindow() {
  const win = new BrowserWindow({
    width: 1200,
    height: 840,
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
    setupAutoUpdate();
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

app.whenReady().then(() => {
  if (!isCloud(loadConfig())) spawnBackend();   // 云模式不拉本地后端
  createWindow();

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

// 退出即清理后端进程组
app.on('before-quit', killBackend);
process.on('exit', killBackend);
