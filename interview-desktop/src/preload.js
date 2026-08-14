// 预加载脚本：把「选文件 / 选文件夹」的桥暴露给渲染进程（window.electronAPI）。
// contextIsolation 开启，必须用 contextBridge，不能让渲染进程直接碰 node。
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('electronAPI', {
  pickFile: () => ipcRenderer.invoke('dialog:pickFile'),
  pickFolder: () => ipcRenderer.invoke('dialog:pickFolder'),
  // LLM key 本机存取：key 只存在用户桌面，不发给服务器持久化（随请求临时带上）
  getLlmKey: () => ipcRenderer.invoke('llm:getKey'),
  setLlmKey: (key) => ipcRenderer.invoke('llm:setKey', key),
});
