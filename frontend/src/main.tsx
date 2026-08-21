import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { HashRouter } from 'react-router-dom';
import { AuthProvider } from './auth/AuthContext';
import { App } from './App';
import { applyPrefs, loadPrefs } from './lib/appearance';
import './styles/global.css';

// 渲染前应用外观偏好（主题 + 字号），避免首屏闪一下默认浅色/字号
applyPrefs(loadPrefs());

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <HashRouter>
      <AuthProvider>
        <App />
      </AuthProvider>
    </HashRouter>
  </StrictMode>,
);
