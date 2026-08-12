/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 桌面端（Electron）下指向本地后端 http://127.0.0.1:8080；留空则走 dev 代理。 */
  readonly VITE_API_BASE?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
