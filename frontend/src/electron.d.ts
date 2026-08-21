// 桌面端（Electron）注入到 window 的桥。网页态下不存在，组件需判空后使用。
export {};

declare global {
  /** 更新状态：主进程通过 update:status 事件推送。 */
  interface UpdateStatus {
    phase:
      | 'checking'
      | 'available'
      | 'downloading'
      | 'downloaded'
      | 'not-available'
      | 'error';
    version?: string;
    percent?: number;
    message?: string;
  }

  interface ElectronAPI {
    /** 弹系统文件选择器，返回绝对路径或 null（用户取消）。 */
    pickFile: () => Promise<string | null>;
    /** 弹系统文件夹选择器，返回绝对路径或 null（用户取消）。 */
    pickFolder: () => Promise<string | null>;
    /** 云模式：在本机读取文件夹内支持的文件字节（base64），交给服务器解析。成功 { name, files }，失败 { error }。 */
    collectPath: (path: string) => Promise<
      | { name: string; files: { name: string; data: string }[] }
      | { error: string }
    >;
    /** 当前是否云模式（后端在服务器）：决定选本地文件夹时走后端读盘还是本机读盘。 */
    isCloud: () => Promise<boolean>;
    /** 读取本机保存的 LLM key（仅桌面端；key 不落服务器，随请求临时带上）。 */
    getLlmKey: () => Promise<string>;
    /** 把 LLM key 保存到本机（Electron userData，系统支持时加密）。 */
    setLlmKey: (key: string) => Promise<boolean>;
    /** 应用版本号（来自 package.json）。 */
    getVersion: () => Promise<string>;
    /** 运行平台（darwin / win32 / linux）。 */
    getPlatform: () => Promise<string>;
    /** 手动检查更新：只检查是否新版本，不下载；结果通过 onUpdateStatus 推送。 */
    checkForUpdates: () => Promise<{ ok?: boolean; error?: string }>;
    /** 下载更新（只下载不安装）：进度通过 onUpdateStatus 推送，完成后 phase=downloaded。 */
    downloadUpdate: () => Promise<{ ok?: boolean; error?: string }>;
    /** 下载完成后调用：Windows 重启安装；macOS 打开下载的 dmg 安装包。 */
    installUpdate: () => Promise<{ ok?: boolean; error?: string }>;
    /** 订阅更新状态；返回取消订阅函数。 */
    onUpdateStatus: (cb: (status: UpdateStatus) => void) => () => void;
  }

  interface Window {
    electronAPI?: ElectronAPI;
  }
}
