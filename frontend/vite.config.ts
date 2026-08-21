import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 开发态：把 /api 代理到本地后端（默认 23333），规避 CORS。
// 生产态：构建出纯静态文件，base 用相对路径，可直接托管或让后端一并 serve。
export default defineConfig({
  plugins: [react()],
  base: './',
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:23333',
        changeOrigin: true,
      },
    },
  },
});
