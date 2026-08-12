// 桌面端（Electron）注入到 window 的桥。网页态下不存在，组件需判空后使用。
export {};

declare global {
  interface ElectronAPI {
    /** 弹系统文件选择器，返回绝对路径或 null（用户取消）。 */
    pickFile: () => Promise<string | null>;
    /** 弹系统文件夹选择器，返回绝对路径或 null（用户取消）。 */
    pickFolder: () => Promise<string | null>;
  }

  interface Window {
    electronAPI?: ElectronAPI;
  }
}
