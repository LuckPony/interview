import {useState, type FormEvent, useEffect} from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowRight } from 'lucide-react';
import { useAuth } from '../auth/AuthContext';
import { Button } from '../components/ui';
import { ApiError } from '../api/client';
import { register as apiRegister, verify as apiVerify } from '../api/auth';
import './Login.css';
import { PuzzleSlider } from '../components/PuzzleSlider';
import { getAuthConfig } from '../api/auth';

type Mode = 'login' | 'register' | 'verify';

export function Login() {
  const { login, completeAuth } = useAuth();
  const navigate = useNavigate();
  const [mode, setMode] = useState<Mode>('login');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);

  const [showCaptcha, setShowCaptcha] = useState(false);
  const [captchaRequired, setCaptchaRequired] = useState(true); // 未知时按需滑块处理（更安全）
  const [pendingRegister, setPendingRegister] = useState(false); // 是否处于“待完成注册”状态
  const [captchaToken, setCaptchaToken] = useState('');

  // 挂载时问一次后端是否需要滑块
  useEffect(() => {
    getAuthConfig()
        .then((c) => setCaptchaRequired(c.captchaRequired))
        .catch(() => { /* 后端没起来时保持默认 true，注册会报错自然引导 */ });
  }, []);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setErr('');
    setBusy(true);
    try {
      if (mode === 'login') {
        await login(email.trim(), password);
        navigate('/', { replace: true });
      } else if (mode === 'register') {
        if (captchaRequired && !pendingRegister) {
          // 第一步：填完邮箱密码，先弹滑块验证（通过后再发邮箱验证码）
          setShowCaptcha(true);
          return;
        }
        const r = await apiRegister(email.trim(), password, captchaToken);
        if (r.verified) {
          completeAuth(r);
          navigate('/', { replace: true });
        } else {
          setMode('verify');      // 滑块过了 + 邮件已发 → 进输码步
        }
      } else {
        const r = await apiVerify(email.trim(), code.trim());
        completeAuth(r);
        navigate('/', { replace: true });
      }
    } catch (e2) {
      setErr(e2 instanceof ApiError ? e2.message : '操作失败，请确认后端已启动');
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
        <div className="login-meta reveal reveal-delay-3">
          {mode === 'verify' ? '验证码已发送，请查收邮箱' : '注册即用 · 进度存在云端'}
        </div>
      </section>

      <section className="login-panel">
        <div className="login-card reveal">
          <h2>{mode === 'login' ? '欢迎回来' : mode === 'register' ? '注册面霸' : '验证邮箱'}</h2>
          <p className="login-sub">
            {mode === 'verify'
              ? `验证码已发送到 ${email}，15 分钟内有效。`
              : mode === 'login'
                ? '登录后继续你的备考进度。'
                : '创建账号，练习记录存在云端，换设备不丢。'}
          </p>

          <form onSubmit={submit}>
            <label className="field">
              <span className="field-label">邮箱</span>
              <input
                type="email"
                autoFocus
                disabled={mode === 'verify'}
                placeholder="you@example.com"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
              />
            </label>

            {mode === 'verify' ? (
              <label className="field">
                <span className="field-label">验证码</span>
                <input
                  inputMode="numeric"
                  placeholder="6 位数字"
                  value={code}
                  onChange={(e) => setCode(e.target.value)}
                />
              </label>
            ) : (
              <label className="field">
                <span className="field-label">密码</span>
                <input
                  type="password"
                  placeholder="至少 6 位"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                />
              </label>
            )}

            {err && <div className="banner info">{err}</div>}

            <Button type="submit" disabled={busy} style={{ width: '100%' }}>
              {busy
                ? mode === 'login'
                  ? '登录中…'
                  : mode === 'register'
                    ? '注册中…'
                    : '验证中…'
                : mode === 'login'
                  ? '登录'
                  : mode === 'register'
                    ? '注册'
                    : '完成验证'}
              {!busy && <ArrowRight size={16} strokeWidth={2} />}
            </Button>
          </form>

          <div className="login-switch">
            {mode === 'login' && (
              <button onClick={() => { setErr(''); setMode('register'); }}>
                没有账号？去注册
              </button>
            )}
            {mode === 'register' && (
              <button onClick={() => { setErr(''); setMode('login'); }}>
                已有账号？去登录
              </button>
            )}
            {mode === 'verify' && (
              <button onClick={() => { setErr(''); setMode('login'); }}>
                换个邮箱，重新登录
              </button>
            )}
          </div>
        </div>
        {showCaptcha && (
            <PuzzleSlider
                onPass={(token) => {
                  setShowCaptcha(false);
                  setCaptchaToken(token);
                  setPendingRegister(true);
                  // 滑块通过 → 真正提交注册（后端随即决定是否发邮箱验证码）
                  void (async () => {
                    try {
                      const r = await apiRegister(email.trim(), password, token);
                      if (r.verified) {
                        completeAuth(r);
                        navigate('/', { replace: true });
                      } else {
                        setMode('verify');
                      }
                    } catch (e2) {
                      setErr(e2 instanceof ApiError ? e2.message : '操作失败，请确认后端已启动');
                    } finally {
                      setPendingRegister(false);
                    }
                  })();
                }}
                onClose={() => setShowCaptcha(false)}
            />
        )}
      </section>
    </div>
  );
}
