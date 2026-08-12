// 桌面壳主进程：拉起本地 Spring Boot 后端 → 探活闸门 → 加载 React SPA → 退出清理。
// 仅做「启动器 + 本地 fs 桥」，不打包 JVM / Docker（见 README 的诚实说明）。

const { app, BrowserWindow, dialog, ipcMain } = require('electron');
const path = require('path');
const { spawn } = require('child_process');
const http = require('http');
const fs = require('fs');

// 防 Electron 的 Chromium 把 127.0.0.1 拐去代理（同 start.sh 的坑）
app.commandLine.appendSwitch('no-proxy-server');

const REPO_ROOT = path.resolve(__dirname, '..', '..'); // interview-desktop/src -> interview/
const BACKEND_PORT = 8080;
const HEALTH_TIMEOUT_MS = 90_000;

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
  small { display: block; margin-top: 20px; color: #8B95A5; font-size: 12.5px; }
</style>
</head>
<body>
  <div class="card">
    <span class="boss">😎</span>
    <h1>面霸 · 启动中</h1>
    <p class="sub">面试霸主正在热身，本地服务马上就好…</p>
    <div class="spinner" role="status"></div>
    <div class="row"><span>💼</span><span>🎯</span><span>☕</span></div>
    <small>Spring Boot 在泡咖啡，首次约需十几秒 —— 稍安勿躁</small>
  </div>
</body>
</html>`;

function spawnBackend() {
  // detached + 进程组，退出时才能连 JVM 一起杀掉（光 kill 直接子进程杀不掉 gradle→JVM）
  // 后端日志（含异常堆栈）重定向到 backend.log，否则 stdio:'ignore' 会把 500 堆栈吞掉，难以排查
  const logPath = path.join(REPO_ROOT, 'interview-desktop', 'backend.log');
  const logFd = fs.openSync(logPath, 'a');
  backend = spawn('bash', ['start.sh'], {
    cwd: REPO_ROOT,
    detached: true,
    stdio: ['ignore', logFd, logFd],
    env: {
      ...process.env,
      // 复活 start.sh 里的去代理环境变量，确保后端只连本机
      JAVA_TOOL_OPTIONS:
        '-Djava.net.useSystemProxies=false -DsocksProxyHost= -Dhttp.nonProxyHosts=localhost|127.0.0.1|*.local',
    },
  });
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

function createWindow() {
  const win = new BrowserWindow({
    width: 1200,
    height: 840,
    backgroundColor: '#F6F8FB',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
    },
  });

  // 先给个加载页，后端就绪后再换 SPA
  // 注意：data: URL 必须声明 charset=utf-8，否则 Chromium 默认按 Latin-1 解码中文会乱码
  win.loadURL('data:text/html;charset=utf-8,' + encodeURIComponent(SPLASH_HTML));

  const spaPath = path.join(app.getAppPath(), 'app-dist', 'index.html');
  waitForBackend(HEALTH_TIMEOUT_MS)
    .then(() => {
      if (fs.existsSync(spaPath)) win.loadFile(spaPath);
      else win.loadURL('http://localhost:5173'); // 没构建 SPA 时回退 dev server
    })
    .catch((e) => {
      dialog.showErrorBox('启动失败', e.message);
      app.quit();
    });

  return win;
}

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

app.whenReady().then(() => {
  spawnBackend();
  createWindow();

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

// 退出即清理后端进程组
app.on('before-quit', killBackend);
process.on('exit', killBackend);
