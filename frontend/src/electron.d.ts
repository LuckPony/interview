// 桌面端（Electron）注入到 window 的桥。网页态下不存在，组件需判空后使用。
export {};

declare global {
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
  }

  interface Window {
    electronAPI?: ElectronAPI;
  }
}
