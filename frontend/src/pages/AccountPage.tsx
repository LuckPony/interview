import { useEffect, useState } from 'react';
import { AtSign, CalendarDays, Check, Mail, Phone, Save, UserRound } from 'lucide-react';
import { ApiError } from '../api/client';
import { userProfileApi, type UpdateUserProfile, type UserProfile } from '../api/user';
import { useAuth } from '../auth/AuthContext';
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

export function AccountPage() {
  const { profile, refreshProfile } = useAuth();
  const [email, setEmail] = useState(profile?.email ?? '');
  const [form, setForm] = useState<UpdateUserProfile>(() => profile ? toForm(profile) : EMPTY_FORM);
  const [loading, setLoading] = useState(!profile);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState('');

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

  return (
    <div className="page account-page">
      <header className="page-head account-head">
        <span className="eyebrow">个人中心 · ACCOUNT</span>
        <h1>个人信息</h1>
        <p>完善你的称呼和基础资料。用户名保存后会立即显示在左下角欢迎区。</p>
      </header>

      {error && <div className="banner">{error}</div>}

      {loading ? (
        <Loading label="读取个人信息…" />
      ) : (
        <div className="account-layout">
          <Card className="account-identity">
            <span className="account-avatar"><UserRound size={32} strokeWidth={1.5} /></span>
            <span className="account-kicker">当前身份</span>
            <h2>{form.username.trim() || '尚未设置用户名'}</h2>
            <p>{form.nickname.trim() || '设置用户名后，侧栏会用更亲切的方式称呼你。'}</p>
            <div className="account-email"><Mail size={15} /> {email}</div>
          </Card>

          <Card className="account-form-card">
            <div className="account-section-head">
              <div>
                <span className="eyebrow">PROFILE</span>
                <h2>基础资料</h2>
              </div>
              {saved && <span className="account-saved"><Check size={15} /> 已保存</span>}
            </div>

            <div className="account-form-grid">
              <label className="account-field">
                <span><AtSign size={15} /> 用户名</span>
                <input
                  value={form.username}
                  maxLength={50}
                  onChange={event => update('username', event.target.value)}
                  placeholder="侧栏优先显示这个名称"
                />
                <small>未设置时将显示随机生成的“霸仔 + 三位字符”。</small>
              </label>

              <label className="account-field">
                <span><UserRound size={15} /> 昵称</span>
                <input
                  value={form.nickname}
                  maxLength={50}
                  onChange={event => update('nickname', event.target.value)}
                  placeholder="可选"
                />
              </label>

              <label className="account-field">
                <span><Phone size={15} /> 手机号</span>
                <input
                  value={form.phone}
                  maxLength={20}
                  inputMode="tel"
                  onChange={event => update('phone', event.target.value)}
                  placeholder="可选"
                />
              </label>

              <label className="account-field">
                <span><CalendarDays size={15} /> 生日</span>
                <input
                  type="date"
                  value={form.birthday ?? ''}
                  onChange={event => update('birthday', event.target.value || null)}
                />
              </label>

              <label className="account-field account-field-wide">
                <span><UserRound size={15} /> 性别</span>
                <select
                  value={form.gender ?? ''}
                  onChange={event => update('gender', (event.target.value || null) as UserProfile['gender'])}
                >
                  <option value="">暂不设置</option>
                  <option value="M">男</option>
                  <option value="F">女</option>
                  <option value="OTHER">其他</option>
                </select>
              </label>

              <label className="account-field account-field-wide">
                <span><Mail size={15} /> 登录邮箱</span>
                <input value={email} disabled />
                <small>邮箱用于登录，目前不支持在这里修改。</small>
              </label>
            </div>

            <div className="account-actions">
              <Button onClick={save} disabled={saving}>
                <Save size={16} strokeWidth={1.8} /> {saving ? '保存中…' : '保存个人信息'}
              </Button>
            </div>
          </Card>
        </div>
      )}
    </div>
  );
}
