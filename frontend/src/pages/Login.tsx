import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowRight } from 'lucide-react';
import { useAuth } from '../auth/AuthContext';
import { Button } from '../components/ui';
import { ApiError } from '../api/client';
import './Login.css';

export function Login() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const [userId, setUserId] = useState('');
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const id = userId.trim();
    if (!id) {
      setErr('请输入面霸编号');
      return;
    }
    setErr('');
    setBusy(true);
    try {
      await login(id);
      navigate('/', { replace: true });
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : '登录失败，请确认后端已启动');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="login">
      <section className="login-hero">
        <span className="eyebrow reveal">面试备考系统 · DRILL</span>
        <h1 className="login-title reveal reveal-delay-1">
          把 AI 当教具，
          <br />
          把判断留给自己。
        </h1>
        <p className="login-lede reveal reveal-delay-2">
          面霸是一套反套路的备考系统：出题与判分交给模型，
          但练什么、判多严、何时复习，全由服务端确定性算法决定。你只管动脑。
        </p>
        <div className="login-meta reveal reveal-delay-3">演示环境 · 输入任意数字编号即可进入</div>
      </section>

      <section className="login-panel">
        <div className="login-card reveal">
          <h2>进入面霸</h2>
          <p className="login-sub">输入你的面霸编号，开启一段闭卷的复习。</p>
          <form onSubmit={submit}>
            <label className="field">
              <span className="field-label">面霸编号</span>
              <input
                autoFocus
                inputMode="numeric"
                placeholder="例如 1"
                value={userId}
                onChange={(e) => setUserId(e.target.value)}
              />
            </label>
            {err && <div className="banner info">{err}</div>}
            <Button type="submit" disabled={busy} style={{ width: '100%' }}>
              进入面霸
              {!busy && <ArrowRight size={16} strokeWidth={2} />}
            </Button>
          </form>
        </div>
      </section>
    </div>
  );
}
