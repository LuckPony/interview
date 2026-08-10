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
    backgroundColor: '#fbf8f3',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
    },
  });

  // 先给个加载页，后端就绪后再换 SPA
  // 注意：data: URL 必须声明 charset=utf-8，否则 Chromium 默认按 Latin-1 解码中文会乱码
  win.loadURL(
    'data:text/html;charset=utf-8,' +
      encodeURIComponent(
        '<meta charset="utf-8"><body style="font-family:system-ui;display:grid;place-items:center;height:100vh;margin:0;color:#6b5b4d;background:#fbf8f3"><div>正在启动本地服务…<br><small>首次约需十几秒（拉起 Spring Boot + Postgres）</small></div></body>'
      )
  );

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
