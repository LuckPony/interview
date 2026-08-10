// 预加载脚本：把「选文件 / 选文件夹」的桥暴露给渲染进程（window.electronAPI）。
// contextIsolation 开启，必须用 contextBridge，不能让渲染进程直接碰 node。
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('electronAPI', {
  pickFile: () => ipcRenderer.invoke('dialog:pickFile'),
  pickFolder: () => ipcRenderer.invoke('dialog:pickFolder'),
});
