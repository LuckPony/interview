import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowRight, Eye, EyeOff, LockKeyhole, Mail, ShieldCheck } from 'lucide-react';
import { useAuth } from '../auth/AuthContext';
import { Button } from '../components/ui';
import { ApiError } from '../api/client';
import { login as apiLogin, register as apiRegister, sendRegisterCode as apiSendRegisterCode, getAuthConfig } from '../api/auth';
import { PuzzleSlider } from '../components/PuzzleSlider';
import logo from '../logo.png';
import offerIllustration from '../assets/login-offer.png';
import './Login.css';

type Mode = 'login' | 'register';

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export function Login() {
  const { completeAuth } = useAuth();
  const navigate = useNavigate();
  const [mode, setMode] = useState<Mode>('login');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);
  const [passwordVisible, setPasswordVisible] = useState(false);
  const credentialBridge = window.electronAPI;
  const hasRemember = !!credentialBridge?.getLoginCredentials && !!credentialBridge.saveLoginCredentials && !!credentialBridge.clearLoginCredentials;
  const [remember, setRemember] = useState(false);
  const [rememberReady, setRememberReady] = useState(!hasRemember);
  const [rememberAvailable, setRememberAvailable] = useState(false);
  const [rememberBusy, setRememberBusy] = useState(false);
  const [rememberNote, setRememberNote] = useState('');
  const edited = useRef(false);
  const filledEmail = useRef('');

  useEffect(() => {
    if (mode !== 'login' || !hasRemember) return;
    let active = true;
    setRememberReady(false);
    credentialBridge!.getLoginCredentials!().then(result => {
      if (!active) return;
      setRememberAvailable(result.available);
      setRememberNote(result.message ?? '仅在这台电脑加密保存，退出登录后仍可填充。');
      setRemember(!!result.credentials);
      if (result.credentials && !edited.current) {
        setEmail(result.credentials.email); setPassword(result.credentials.password);
        filledEmail.current = result.credentials.email;
      }
    }).catch(() => { if (active) setRememberNote('无法读取本机登录信息，仍可手动登录。'); })
      .finally(() => { if (active) setRememberReady(true); });
    return () => { active = false; };
  }, [mode, hasRemember, credentialBridge]);

  const clearRemembered = async (clearInput = true) => {
    setRememberBusy(true);
    try {
      await credentialBridge?.clearLoginCredentials?.();
      setRemember(false); if (clearInput) setPassword(''); filledEmail.current = ''; edited.current = true;
      setRememberNote('已清除这台电脑保存的登录信息。');
    } catch { setRememberNote('清除失败，请重试；本机保存的密码尚未删除。'); }
    finally { setRememberBusy(false); }
  };

  // —— 环境开关（config 未返回前按最严格处理，返回后更新）——
  const [captchaRequired, setCaptchaRequired] = useState(true);
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
    edited.current = true;
    if (filledEmail.current && filledEmail.current.toLowerCase() !== v.trim().toLowerCase()) {
      setPassword(''); filledEmail.current = '';
    }
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
    edited.current = true;
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
      setErr(e2 instanceof ApiError ? e2.message : '验证码发送失败，请检查网络后重试');
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
        const response = await apiLogin(email.trim(), password);
        // 只有认证成功才覆盖保存的凭据，错误密码不会被记住。
        if (hasRemember) {
          try {
            if (remember) await credentialBridge!.saveLoginCredentials!({ email: email.trim(), password });
            else await credentialBridge!.clearLoginCredentials!();
          } catch {
            setErr('登录验证成功，但本机密码保存失败。请取消记住密码后重试。');
            return;
          }
        }
        completeAuth(response);
        navigate('/', { replace: true });
      } catch (e2) {
        setErr(e2 instanceof ApiError ? e2.message : '无法连接服务，请检查网络后重试');
      } finally {
        setBusy(false);
      }
      return;
    }

    // —— register ——
    if (!checkBaseInput()) return;
    if (!codeSent) {
      setErr('请先点击「获取验证码」，输入邮箱收到的验证码后再注册');
      return;
    }
    if (!/^\d{6}$/.test(code.trim())) {
      setErr('请输入 6 位邮箱验证码');
      return;
    }
    setBusy(true);
    try {
      const r = await apiRegister(email.trim(), password, code.trim());
      completeAuth(r);
      navigate('/', { replace: true });
    } catch (e2) {
      setErr(e2 instanceof ApiError ? e2.message : '无法连接服务，请检查网络后重试');
    } finally {
      setBusy(false);
    }
  };

  const switchMode = () => {
    setErr('');
    setSentNote('');
    setEmailHint('');
    setPwHint('');
    setPasswordVisible(false);
    setPassword(''); filledEmail.current = ''; edited.current = false;
    setMode(mode === 'login' ? 'register' : 'login');
  };

  return (
    <main className="auth-page">
      <div className="auth-shell">
        <section className="auth-hero" aria-labelledby="auth-hero-title">
          <img className="auth-illustration" src={offerIllustration}
            alt="书本组成学习阶梯，顶端是一封象征新机会的 Offer 信封" />
          <div className="auth-brand">
            <img src={logo} alt="" width="36" height="36" />
            <span>面霸<span className="auth-brand-en">MIANBA</span></span>
          </div>
          <div className="auth-hero-copy">
            <p className="auth-eyebrow">每一份收获，都从准备开始</p>
            <h1 id="auth-hero-title">把努力，<br />变成下一份 <span>Offer。</span></h1>
            <p className="auth-hero-description">从学会一个知识点，到从容面对每一次面试。</p>
            <div className="auth-hero-footer"><span>学习有方向，成长有回响</span><span aria-hidden="true">↗</span></div>
          </div>
        </section>

        <section className="auth-panel" aria-labelledby="auth-form-title">
          <div className="auth-switch">
            <span>{mode === 'login' ? '还没有账号？' : '已经有账号？'}</span>
            <button type="button" onClick={switchMode} disabled={busy || sendingCode}>
              {mode === 'login' ? '创建账号' : '去登录'}<ArrowRight size={14} aria-hidden="true" />
            </button>
          </div>

          <div className="auth-form-wrap">
            <div className="auth-form-intro">
              <span className="auth-form-eyebrow">{mode === 'login' ? 'WELCOME BACK' : 'YOUR NEXT CHAPTER'}</span>
              <h2 id="auth-form-title">{mode === 'login' ? '欢迎回来' : '开启你的成长之旅'}</h2>
              <p>{mode === 'login' ? '登录面霸，继续向理想的自己迈进一步。' : '创建面霸账号，让每一次准备都更有方向。'}</p>
            </div>

            <form className="auth-form" onSubmit={submit} aria-busy={busy}>
              <div className="auth-field">
                <label htmlFor="auth-email">邮箱地址</label>
                <div className="auth-input-wrap">
                  <Mail size={17} strokeWidth={1.6} aria-hidden="true" />
                  <input id="auth-email" name="email" type="email" autoComplete="username"
                    autoCapitalize="none" spellCheck={false} placeholder="请输入你的邮箱"
                    required value={email} disabled={busy || sendingCode}
                    aria-invalid={!!emailHint} aria-describedby={emailHint ? 'auth-email-hint' : undefined}
                    onChange={(e) => onEmailChange(e.target.value)} />
                </div>
                {emailHint && <span id="auth-email-hint" className="auth-hint">{emailHint}</span>}
              </div>

              <div className="auth-field">
                <label htmlFor="auth-password">密码</label>
                <div className="auth-input-wrap">
                  <LockKeyhole size={17} strokeWidth={1.6} aria-hidden="true" />
                  <input id="auth-password" name="password" type={passwordVisible ? 'text' : 'password'}
                    autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
                    placeholder={mode === 'login' ? '请输入密码' : '设置密码，至少 6 位'}
                    required minLength={6} maxLength={64} value={password} disabled={busy || sendingCode}
                    aria-invalid={!!pwHint} aria-describedby={pwHint ? 'auth-password-hint' : undefined}
                    onChange={(e) => onPasswordChange(e.target.value)} />
                  <button type="button" className="auth-password-toggle" aria-label={passwordVisible ? '隐藏密码' : '显示密码'}
                    aria-pressed={passwordVisible} onClick={() => setPasswordVisible(!passwordVisible)}>
                    {passwordVisible ? <EyeOff size={17} strokeWidth={1.6} /> : <Eye size={17} strokeWidth={1.6} />}
                  </button>
                </div>
                {pwHint && <span id="auth-password-hint" className="auth-hint">{pwHint}</span>}
              </div>

              {mode === 'login' && hasRemember && <div className="auth-remember">
                <div><label><input type="checkbox" checked={remember}
                  disabled={!rememberReady || !rememberAvailable || rememberBusy || busy}
                  onChange={event => { if (event.target.checked) setRemember(true); else void clearRemembered(false); }} />记住密码</label>
                  <button type="button" disabled={!rememberReady || rememberBusy || busy} onClick={() => void clearRemembered()}>清除已保存</button></div>
                <small role="status">{rememberNote || '正在检查本机加密存储…'}</small>
              </div>}

              {mode === 'register' && (
                <div className="auth-field">
                  <label htmlFor="auth-code">邮箱验证码</label>
                  <div className="auth-code-row">
                    <div className="auth-input-wrap">
                      <ShieldCheck size={17} strokeWidth={1.6} aria-hidden="true" />
                      <input id="auth-code" name="code" inputMode="numeric" autoComplete="one-time-code"
                        placeholder="6 位验证码" maxLength={6} required pattern="[0-9]{6}" value={code}
                        disabled={busy} onChange={(e) => {
                          setCode(e.target.value.replace(/\D/g, '').slice(0, 6));
                          setErr('');
                        }} />
                    </div>
                    <button type="button" className="auth-send-code"
                      disabled={busy || sendingCode || resendIn > 0 || !configLoaded} onClick={handleGetCode}>
                      {sendingCode ? '发送中…' : resendIn > 0 ? `${resendIn}s 后重发` : codeSent ? '重新获取' : '获取验证码'}
                    </button>
                  </div>
                  {sentNote && <span className="auth-note" role="status">{sentNote}</span>}
                </div>
              )}

              {err && <div className="auth-error" role="alert">{err}</div>}
              <Button type="submit" className="auth-submit" disabled={busy || rememberBusy || (mode === 'login' && !rememberReady) || (mode === 'register' && (!configLoaded || sendingCode))}>
                {busy ? mode === 'login' ? '登录中…' : '创建中…' : mode === 'login' ? '登录' : '创建账号'}
                {!busy && <ArrowRight size={17} strokeWidth={1.8} aria-hidden="true" />}
              </Button>
            </form>
            <p className="auth-form-note"><ShieldCheck size={14} strokeWidth={1.6} aria-hidden="true" />你的每一步进步，都值得被认真记录</p>
          </div>

          <footer className="auth-panel-footer"><span>AI 陪练</span><span>系统学习</span><span>面试复盘</span></footer>
        </section>
      </div>
      {showCaptcha && (
        <PuzzleSlider onPass={(token) => { setShowCaptcha(false); void doSendCode(token); }}
          onClose={() => setShowCaptcha(false)} />
      )}
    </main>
  );
}
