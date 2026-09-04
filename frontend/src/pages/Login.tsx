import { useEffect, useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowRight } from 'lucide-react';
import { useAuth } from '../auth/AuthContext';
import { Button } from '../components/ui';
import { ApiError } from '../api/client';
import { register as apiRegister, sendRegisterCode as apiSendRegisterCode, getAuthConfig } from '../api/auth';
import { PuzzleSlider } from '../components/PuzzleSlider';
import './Login.css';

type Mode = 'login' | 'register';

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export function Login() {
  const { login, completeAuth } = useAuth();
  const navigate = useNavigate();
  const [mode, setMode] = useState<Mode>('login');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);

  // —— 环境开关（config 未返回前按最严格处理，返回后更新）——
  const [captchaRequired, setCaptchaRequired] = useState(true);
  const [emailVerifyRequired, setEmailVerifyRequired] = useState(true);
  const [configLoaded, setConfigLoaded] = useState(false);

  // —— 注册取码状态 ——
  const [showCaptcha, setShowCaptcha] = useState(false);
  const [sendingCode, setSendingCode] = useState(false);
  const [codeSent, setCodeSent] = useState(false);
  const [resendIn, setResendIn] = useState(0);
  const [sentNote, setSentNote] = useState('');

  // —— 输入即时校验 ——
  const [emailHint, setEmailHint] = useState('');
  const [pwHint, setPwHint] = useState('');

  useEffect(() => {
    getAuthConfig()
      .then((c) => {
        setCaptchaRequired(c.captchaRequired);
        setEmailVerifyRequired(c.emailVerifyRequired);
      })
      .catch(() => { /* 后端未启动时保持默认，提交时会自然报错 */ })
      .finally(() => setConfigLoaded(true));
  }, []);

  // 重发倒计时
  useEffect(() => {
    if (resendIn <= 0) return;
    const t = window.setTimeout(() => setResendIn((n) => n - 1), 1000);
    return () => window.clearTimeout(t);
  }, [resendIn]);

  const validEmail = (v: string) => EMAIL_RE.test(v.trim());

  const onEmailChange = (v: string) => {
    setEmail(v);
    setErr('');
    // 邮箱变了 → 之前收到的验证码作废，需重新获取
    setCode('');
    setCodeSent(false);
    setResendIn(0);
    setSentNote('');
    if (v.trim() === '') setEmailHint('');
    else if (!validEmail(v)) setEmailHint('邮箱格式不正确，请检查后再试');
    else setEmailHint('');
  };

  const onPasswordChange = (v: string) => {
    setPassword(v);
    setErr('');
    setPwHint(v.length > 0 && v.length < 6 ? '密码至少 6 位' : '');
  };

  /** 校验邮箱+密码是否可提交（注册前的基础输入检查）。 */
  const checkBaseInput = (): boolean => {
    if (!email.trim()) { setEmailHint('请输入邮箱'); return false; }
    if (!validEmail(email)) { setEmailHint('邮箱格式不正确，请检查后再试'); return false; }
    setEmailHint('');
    if (password.length < 6) { setPwHint('密码至少 6 位'); return false; }
    setPwHint('');
    return true;
  };

  /** 实际调用后端发码（滑块通过后或滑块关闭时直接调用）。 */
  const doSendCode = async (captchaToken?: string) => {
    setSendingCode(true);
    setErr('');
    try {
      await apiSendRegisterCode(email.trim(), captchaToken);
      setCodeSent(true);
      setResendIn(60);
      setSentNote(`验证码已发送至 ${email.trim()}，15 分钟内有效。`);
    } catch (e2) {
      setErr(e2 instanceof ApiError ? e2.message : '验证码发送失败，请确认后端已启动');
    } finally {
      setSendingCode(false);
    }
  };

  /** 点「获取验证码」：先本地校验，再决定是否弹滑块。 */
  const handleGetCode = () => {
    setErr('');
    setSentNote('');
    if (!checkBaseInput()) return;
    if (captchaRequired) setShowCaptcha(true);
    else void doSendCode(undefined);
  };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setErr('');
    setSentNote('');
    if (mode === 'login') {
      if (!email.trim() || !validEmail(email)) { setEmailHint('请输入正确的邮箱'); return; }
      setBusy(true);
      try {
        await login(email.trim(), password);
        navigate('/', { replace: true });
      } catch (e2) {
        setErr(e2 instanceof ApiError ? e2.message : '操作失败，请确认后端已启动');
      } finally {
        setBusy(false);
      }
      return;
    }

    // —— register ——
    if (!checkBaseInput()) return;
    if (emailVerifyRequired) {
      if (!codeSent) {
        setErr('请先点击「获取验证码」，输入邮箱收到的验证码后再注册');
        return;
      }
      if (!code.trim()) { setErr('请输入邮箱收到的验证码'); return; }
    }
    setBusy(true);
    try {
      const r = await apiRegister(email.trim(), password, code.trim());
      completeAuth(r);
      navigate('/', { replace: true });
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
          {mode === 'register' ? '先取验证码，验证通过才创建账号' : '注册即用 · 进度存在云端'}
        </div>
      </section>

      <section className="login-panel">
        <div className="login-card reveal">
          <h2>{mode === 'login' ? '欢迎回来' : '注册面霸'}</h2>
          <p className="login-sub">
            {mode === 'login'
              ? '登录后继续你的备考进度。'
              : '输入邮箱与密码，先获取邮箱验证码，通过后即可完成注册。'}
          </p>

          <form onSubmit={submit}>
            <label className="field">
              <span className="field-label">邮箱</span>
              <input
                type="email"
                autoFocus
                placeholder="you@example.com"
                value={email}
                onChange={(e) => onEmailChange(e.target.value)}
              />
              {emailHint && <span className="field-hint">{emailHint}</span>}
            </label>

            <label className="field">
              <span className="field-label">密码</span>
              <input
                type="password"
                placeholder="至少 6 位"
                value={password}
                onChange={(e) => onPasswordChange(e.target.value)}
              />
              {pwHint && <span className="field-hint">{pwHint}</span>}
            </label>

            {mode === 'register' && emailVerifyRequired && (
              <div className="field">
                <span className="field-label">邮箱验证码</span>
                <div className="code-row">
                  <input
                    className="code-input"
                    inputMode="numeric"
                    placeholder="6 位数字"
                    value={code}
                    onChange={(e) => { setCode(e.target.value); setErr(''); }}
                  />
                  <button
                    type="button"
                    className="send-btn"
                    disabled={busy || sendingCode || resendIn > 0 || !configLoaded}
                    onClick={handleGetCode}
                  >
                    {sendingCode
                      ? '发送中…'
                      : resendIn > 0
                        ? `${resendIn}s 后重发`
                        : codeSent
                          ? '重新获取'
                          : '获取验证码'}
                  </button>
                </div>
                {sentNote && <span className="field-note">{sentNote}</span>}
              </div>
            )}

            {mode === 'register' && !emailVerifyRequired && configLoaded && (
              <p className="login-sub" style={{ marginTop: 0 }}>
                当前环境未启用邮箱验证，点击注册即可直接创建账号。
              </p>
            )}

            {err && <div className="banner">{err}</div>}

            <Button type="submit" disabled={busy} style={{ width: '100%' }}>
              {busy
                ? mode === 'login' ? '登录中…' : '注册中…'
                : mode === 'login' ? '登录' : '注册'}
              {!busy && <ArrowRight size={16} strokeWidth={2} />}
            </Button>
          </form>

          <div className="login-switch">
            {mode === 'login' && (
              <button onClick={() => { setErr(''); setSentNote(''); setMode('register'); }}>
                没有账号？去注册
              </button>
            )}
            {mode === 'register' && (
              <button onClick={() => { setErr(''); setSentNote(''); setMode('login'); }}>
                已有账号？去登录
              </button>
            )}
          </div>
        </div>

        {showCaptcha && (
          <PuzzleSlider
            onPass={(token) => {
              setShowCaptcha(false);
              void doSendCode(token);
            }}
            onClose={() => setShowCaptcha(false)}
          />
        )}
      </section>
    </div>
  );
}
