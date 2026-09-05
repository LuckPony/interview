import { useEffect, useMemo, useState, type CSSProperties } from 'react';
import {
  AtSign,
  CalendarDays,
  Check,
  CheckCircle2,
  Eye,
  EyeOff,
  KeyRound,
  LockKeyhole,
  Mail,
  Phone,
  Save,
  ShieldCheck,
  Sparkles,
  UserRound,
  X,
} from 'lucide-react';
import { ApiError } from '../api/client';
import {
  userPasswordApi,
  userProfileApi,
  type UpdateUserProfile,
  type UserProfile,
} from '../api/user';
import { useAuth } from '../auth/AuthContext';
import { PuzzleSlider } from '../components/PuzzleSlider';
import { Button, Card, Loading } from '../components/ui';
import './AccountPage.css';

const EMPTY_FORM: UpdateUserProfile = {
  username: '',
  nickname: '',
  gender: null,
  phone: '',
  birthday: null,
};

function message(error: unknown): string {
  if (error instanceof ApiError || error instanceof Error) return error.message;
  return '操作失败，请稍后重试';
}

function toForm(profile: UserProfile): UpdateUserProfile {
  return {
    username: profile.username ?? '',
    nickname: profile.nickname ?? '',
    gender: profile.gender,
    phone: profile.phone ?? '',
    birthday: profile.birthday,
  };
}

function passwordStrength(password: string): number {
  if (!password) return 0;
  let score = password.length >= 6 ? 1 : 0;
  if (password.length >= 10) score += 1;
  if (/[A-Za-z]/.test(password) && /\d/.test(password)) score += 1;
  if (/[^A-Za-z0-9]/.test(password)) score += 1;
  return Math.min(score, 4);
}

function maskEmail(email: string): string {
  const at = email.indexOf('@');
  if (at <= 1) return `***${email.slice(Math.max(0, at))}`;
  return `${email.slice(0, 2)}***${email.slice(at)}`;
}

export function AccountPage() {
  const { profile, refreshProfile } = useAuth();
  const [email, setEmail] = useState(profile?.email ?? '');
  const [form, setForm] = useState<UpdateUserProfile>(() => profile ? toForm(profile) : EMPTY_FORM);
  const [loading, setLoading] = useState(!profile);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState('');

  const [showPuzzle, setShowPuzzle] = useState(false);
  const [requestingCode, setRequestingCode] = useState(false);
  const [showPasswordDialog, setShowPasswordDialog] = useState(false);
  const [emailHint, setEmailHint] = useState('');
  const [passwordCodeSent, setPasswordCodeSent] = useState(false);
  const [code, setCode] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [changingPassword, setChangingPassword] = useState(false);
  const [passwordError, setPasswordError] = useState('');
  const [passwordChanged, setPasswordChanged] = useState(false);

  useEffect(() => {
    let active = true;
    const load = async () => {
      try {
        const current = profile ?? await userProfileApi.get();
        if (!active) return;
        setEmail(current.email);
        setForm(toForm(current));
      } catch (e) {
        if (active) setError(message(e));
      } finally {
        if (active) setLoading(false);
      }
    };
    void load();
    return () => { active = false; };
    // 首次进入时读取一次即可，避免保存后 AuthContext 刷新覆盖用户正在输入的内容。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (!showPasswordDialog) return;
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !changingPassword) setShowPasswordDialog(false);
    };
    window.addEventListener('keydown', closeOnEscape);
    return () => window.removeEventListener('keydown', closeOnEscape);
  }, [showPasswordDialog, changingPassword]);

  const completion = useMemo(() => {
    const values = [form.username, form.nickname, form.gender, form.phone, form.birthday];
    return Math.round((values.filter(Boolean).length / values.length) * 100);
  }, [form]);
  const strength = passwordStrength(newPassword);

  const update = <K extends keyof UpdateUserProfile>(key: K, value: UpdateUserProfile[K]) => {
    setForm(current => ({ ...current, [key]: value }));
    setSaved(false);
  };

  const save = async () => {
    setSaving(true);
    setSaved(false);
    setError('');
    try {
      const next = await userProfileApi.update(form);
      setEmail(next.email);
      setForm(toForm(next));
      await refreshProfile();
      setSaved(true);
    } catch (e) {
      setError(message(e));
    } finally {
      setSaving(false);
    }
  };

  const startPasswordChange = () => {
    setPasswordError('');
    setPasswordChanged(false);
    setPasswordCodeSent(false);
    setShowPuzzle(true);
  };

  const afterPuzzlePass = async (captchaToken: string) => {
    setShowPuzzle(false);
    setRequestingCode(true);
    setPasswordError('');
    setEmailHint(maskEmail(email));
    setCode('');
    setNewPassword('');
    setConfirmPassword('');
    setPasswordChanged(false);
    setPasswordCodeSent(false);
    setShowPasswordDialog(true);
    try {
      const result = await userPasswordApi.sendCode(captchaToken);
      setEmailHint(result.emailHint);
      setPasswordCodeSent(true);
    } catch (e) {
      setPasswordError(message(e));
    } finally {
      setRequestingCode(false);
    }
  };

  const applyPassword = async () => {
    const normalizedCode = code.trim();
    if (!/^\d{6}$/.test(normalizedCode)) {
      setPasswordError('请输入邮件中的 6 位数字验证码');
      return;
    }
    if (newPassword.length < 6 || newPassword.length > 64) {
      setPasswordError('新密码长度需为 6-64 位');
      return;
    }
    if (newPassword !== confirmPassword) {
      setPasswordError('两次输入的新密码不一致');
      return;
    }
    setChangingPassword(true);
    setPasswordError('');
    try {
      await userPasswordApi.change(normalizedCode, newPassword);
      setPasswordChanged(true);
      setCode('');
      setNewPassword('');
      setConfirmPassword('');
    } catch (e) {
      setPasswordError(message(e));
    } finally {
      setChangingPassword(false);
    }
  };

  return (
    <div className="page account-page">
      <header className="page-head account-head">
        <span className="eyebrow">个人中心 · ACCOUNT</span>
        <h1>你的个人空间</h1>
        <p>管理公开称呼、基础资料与账号安全。资料仅用于优化你的学习与面试体验。</p>
      </header>

      {error && <div className="banner">{error}</div>}

      {loading ? (
        <Loading label="读取个人信息…" />
      ) : (
        <>
          <Card className="account-identity">
            <span className="account-avatar" aria-hidden>
              {form.username.trim().slice(0, 1) || <UserRound size={30} strokeWidth={1.5} />}
            </span>
            <div className="account-identity-copy">
              <span className="account-kicker">当前账号</span>
              <h2>{form.username.trim() || '尚未设置用户名'}</h2>
              <p>{form.nickname.trim() ? `昵称：${form.nickname.trim()}` : '完善资料，让面霸用更适合你的方式陪你学习。'}</p>
              <div className="account-email"><Mail size={14} /> {email}</div>
            </div>
            <div className="account-completion" aria-label={`资料完整度 ${completion}%`}>
              <div className="account-completion-ring" style={{ '--completion': `${completion * 3.6}deg` } as CSSProperties}>
                <span>{completion}%</span>
              </div>
              <div><strong>资料完整度</strong><small>补充信息可完善个人档案</small></div>
            </div>
          </Card>

          <div className="account-layout">
            <Card className="account-form-card">
              <div className="account-section-head">
                <div>
                  <span className="eyebrow">PROFILE</span>
                  <h2>基础资料</h2>
                  <p>这些信息可随时调整，登录邮箱除外。</p>
                </div>
                {saved && <span className="account-saved"><Check size={15} /> 已保存</span>}
              </div>

              <div className="account-form-grid">
                <label className="account-field">
                  <span><AtSign size={15} /> 用户名</span>
                  <input value={form.username} maxLength={50} onChange={event => update('username', event.target.value)} placeholder="侧栏优先显示这个名称" />
                  <small>未设置时将显示随机生成的“霸仔 + 三位字符”。</small>
                </label>
                <label className="account-field">
                  <span><UserRound size={15} /> 昵称</span>
                  <input value={form.nickname} maxLength={50} onChange={event => update('nickname', event.target.value)} placeholder="你的常用称呼（可选）" />
                </label>
                <label className="account-field">
                  <span><Phone size={15} /> 手机号</span>
                  <input value={form.phone} maxLength={20} inputMode="tel" onChange={event => update('phone', event.target.value)} placeholder="可选" />
                </label>
                <label className="account-field">
                  <span><CalendarDays size={15} /> 生日</span>
                  <input type="date" value={form.birthday ?? ''} onChange={event => update('birthday', event.target.value || null)} />
                </label>
                <label className="account-field">
                  <span><UserRound size={15} /> 性别</span>
                  <select value={form.gender ?? ''} onChange={event => update('gender', (event.target.value || null) as UserProfile['gender'])}>
                    <option value="">暂不设置</option>
                    <option value="M">男</option>
                    <option value="F">女</option>
                    <option value="OTHER">其他</option>
                  </select>
                </label>
                <label className="account-field">
                  <span><Mail size={15} /> 登录邮箱</span>
                  <input value={email} disabled />
                  <small>邮箱用于登录和接收安全验证码。</small>
                </label>
              </div>

              <div className="account-actions">
                <Button onClick={save} disabled={saving}>
                  <Save size={16} strokeWidth={1.8} /> {saving ? '保存中…' : '保存个人信息'}
                </Button>
              </div>
            </Card>

            <aside className="account-side-column">
              <Card className="account-security-card">
                <span className="account-security-icon"><ShieldCheck size={23} /></span>
                <span className="eyebrow">SECURITY</span>
                <h2>账号安全</h2>
                <p>修改密码前需通过登录邮箱确认本人操作。</p>
                <div className="account-security-status"><CheckCircle2 size={15} /> 登录邮箱已绑定</div>
                {passwordError && !showPasswordDialog && <div className="account-inline-error">{passwordError}</div>}
                <button type="button" className="account-password-button" onClick={startPasswordChange} disabled={requestingCode}>
                  {requestingCode ? <LoaderLabel /> : <><KeyRound size={16} /> 修改密码</>}
                </button>
              </Card>

              <Card className="account-tip-card">
                <Sparkles size={18} />
                <div><strong>更贴合你的学习体验</strong><p>用户名会显示在欢迎区，昵称等资料不会公开展示。</p></div>
              </Card>
            </aside>
          </div>
        </>
      )}

      {showPuzzle && <PuzzleSlider onPass={afterPuzzlePass} onClose={() => setShowPuzzle(false)} />}

      {showPasswordDialog && (
        <div className="password-modal-overlay" role="presentation" onMouseDown={() => !changingPassword && setShowPasswordDialog(false)}>
          <section className="password-modal" role="dialog" aria-modal="true" aria-labelledby="password-modal-title" onMouseDown={event => event.stopPropagation()}>
            <button type="button" className="password-modal-close" onClick={() => setShowPasswordDialog(false)} disabled={changingPassword} aria-label="关闭">
              <X size={18} />
            </button>

            {passwordChanged ? (
              <div className="password-success">
                <span><CheckCircle2 size={30} /></span>
                <h2 id="password-modal-title">密码修改成功</h2>
                <p>新密码已经生效，下次登录请使用新密码。</p>
                <Button onClick={() => setShowPasswordDialog(false)}>完成</Button>
              </div>
            ) : (
              <>
                <div className="password-modal-head">
                  <span className="password-modal-icon"><LockKeyhole size={23} /></span>
                  <div><span className="eyebrow">CHANGE PASSWORD</span><h2 id="password-modal-title">设置新密码</h2></div>
                </div>
                <div className={`password-mail-notice${!requestingCode && !passwordCodeSent ? ' is-error' : ''}`}>
                  {requestingCode ? <span className="spinner" aria-hidden /> : <Mail size={17} />}
                  <div>
                    <strong>{requestingCode ? '正在发送验证码' : passwordCodeSent ? '验证码已经发送' : '验证码未发送'}</strong>
                    <p>{requestingCode
                      ? '正在确认账号并发送安全邮件…'
                      : passwordCodeSent
                        ? `请查看邮箱 ${emailHint}，验证码 15 分钟内有效。`
                        : '请关闭窗口后重新完成图像验证。'}</p>
                  </div>
                </div>

                {passwordError && <div className="account-inline-error">{passwordError}</div>}

                <div className="password-form">
                  <label className="account-field">
                    <span><Mail size={15} /> 邮箱验证码</span>
                    <input value={code} onChange={event => setCode(event.target.value.replace(/\D/g, '').slice(0, 6))} inputMode="numeric" autoComplete="one-time-code" placeholder="输入 6 位验证码" autoFocus disabled={requestingCode} />
                  </label>
                  <label className="account-field">
                    <span><LockKeyhole size={15} /> 新密码</span>
                    <div className="password-input-wrap">
                      <input type={showPassword ? 'text' : 'password'} value={newPassword} onChange={event => setNewPassword(event.target.value)} autoComplete="new-password" placeholder="6-64 位，建议包含字母和数字" />
                      <button type="button" onClick={() => setShowPassword(current => !current)} aria-label={showPassword ? '隐藏密码' : '显示密码'}>
                        {showPassword ? <EyeOff size={17} /> : <Eye size={17} />}
                      </button>
                    </div>
                    <div className="password-strength" aria-label={`密码强度 ${strength}/4`}>
                      {[1, 2, 3, 4].map(level => <i className={strength >= level ? 'active' : ''} key={level} />)}
                    </div>
                  </label>
                  <label className="account-field">
                    <span><ShieldCheck size={15} /> 确认新密码</span>
                    <input type={showPassword ? 'text' : 'password'} value={confirmPassword} onChange={event => setConfirmPassword(event.target.value)} autoComplete="new-password" placeholder="再次输入新密码" onKeyDown={event => { if (event.key === 'Enter') void applyPassword(); }} />
                  </label>
                </div>

                <div className="password-modal-actions">
                  <button type="button" className="password-resend" onClick={() => { setShowPasswordDialog(false); setShowPuzzle(true); }} disabled={changingPassword}>
                    没收到？重新验证发送
                  </button>
                  <Button onClick={() => void applyPassword()} disabled={!passwordCodeSent || requestingCode || changingPassword}>{changingPassword ? '修改中…' : '应用修改'}</Button>
                </div>
              </>
            )}
          </section>
        </div>
      )}
    </div>
  );
}

function LoaderLabel() {
  return <><span className="spinner" aria-hidden /> 正在发送验证码…</>;
}
