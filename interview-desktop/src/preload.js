// 预加载脚本：把「选文件 / 选文件夹」的桥暴露给渲染进程（window.electronAPI）。
// contextIsolation 开启，必须用 contextBridge，不能让渲染进程直接碰 node。
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('electronAPI', {
  pickFile: () => ipcRenderer.invoke('dialog:pickFile'),
  pickFolder: () => ipcRenderer.invoke('dialog:pickFolder'),
  // 云模式（后端在服务器）：在本机读文件夹字节交给服务器解析（服务器读不到本机路径）
  collectPath: (path) => ipcRenderer.invoke('fs:collectPath', path),
  // 当前是否云模式：决定选本地文件夹时走后端读盘还是本机读盘
  isCloud: () => ipcRenderer.invoke('app:isCloud'),
  // LLM key 本机存取：key 只存在用户桌面，不发给服务器持久化（随请求临时带上）
  getLlmKey: () => ipcRenderer.invoke('llm:getKey'),
  setLlmKey: (key) => ipcRenderer.invoke('llm:setKey', key),
  // 版本号 + 检查更新
  getVersion: () => ipcRenderer.invoke('app:getVersion'),
  getPlatform: () => ipcRenderer.invoke('app:getPlatform'),
  checkForUpdates: () => ipcRenderer.invoke('app:checkForUpdates'),
  installUpdate: () => ipcRenderer.invoke('app:installUpdate'),
  // 订阅更新状态（checking / available / downloading / downloaded / not-available / error）
  onUpdateStatus: (cb) => {
    const handler = (_e, status) => cb(status);
    ipcRenderer.on('update:status', handler);
    return () => ipcRenderer.removeListener('update:status', handler);
  },
});
