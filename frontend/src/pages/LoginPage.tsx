import { FormEvent, useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { LogIn, UserPlus } from 'lucide-react';
import { authApi } from '../api/auth';
import { getAccessToken } from '../api/authStorage';
import { resolveSafeReturnTo } from '../api/authNavigation';
import { getErrorMessage } from '../api/request';
import LoadingButtonContent from '../components/LoadingButtonContent';

type AuthMode = 'login' | 'register';
const AUTH_MODES: AuthMode[] = ['login', 'register'];

export default function LoginPage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const [mode, setMode] = useState<AuthMode>('login');
  const [username, setUsername] = useState('');
  const [email, setEmail] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [password, setPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const from = resolveSafeReturnTo(searchParams.get('from'));
  const isRegister = mode === 'register';

  useEffect(() => {
    document.title = `${isRegister ? '注册' : '登录'} · AI 面试平台`;
  }, [isRegister]);

  useEffect(() => {
    if (getAccessToken()?.trim()) {
      navigate(from, { replace: true });
    }
  }, [from, navigate]);

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setLoading(true);
    setError('');

    try {
      if (isRegister) {
        await authApi.register({
          username: username.trim(),
          email: email.trim(),
          password,
          displayName: displayName.trim() || undefined,
        });
      } else {
        await authApi.login({ username: username.trim(), password });
      }
      navigate(from, { replace: true });
    } catch (err) {
      setError(getErrorMessage(err, '操作失败，请重试'));
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="flex min-h-screen items-center justify-center p-4">
      <div className="surface-card w-full max-w-sm p-6 md:p-7">
        <div className="mb-6 flex items-center gap-3">
          <img
            src="/bear-doctor-logo.png"
            alt="AI面试平台"
            className="w-12 h-12 rounded-full object-cover shrink-0 ring-1 ring-stone-200/80 dark:ring-stone-700"
          />
          <div>
            <h1 className="text-xl font-display font-semibold text-stone-900 dark:text-stone-50">
              {isRegister ? '创建账号' : '欢迎回来'}
            </h1>
            <p className="text-sm text-stone-500 dark:text-stone-400 mt-0.5">
              {isRegister ? '保存你的面试记录和学习资料' : '登录后继续准备面试'}
            </p>
          </div>
        </div>

        <div className="mb-5 grid grid-cols-2 rounded-lg bg-stone-100 dark:bg-stone-900 p-1">
          {AUTH_MODES.map((item) => (
            <button
              key={item}
              type="button"
              onClick={() => {
                setMode(item);
                setError('');
              }}
              className={`rounded-md px-3 py-2 text-sm font-medium transition-colors ${
                mode === item
                  ? 'bg-white dark:bg-stone-950 text-primary-700 shadow-sm'
                  : 'text-stone-500 dark:text-stone-400 hover:text-stone-800 dark:hover:text-stone-100'
              }`}
            >
              {item === 'login' ? '登录' : '注册'}
            </button>
          ))}
        </div>

        <form onSubmit={handleSubmit} className="space-y-4">
          <label className="block">
            <span className="text-sm font-medium text-slate-700 dark:text-slate-300">用户名</span>
            <input
              value={username}
              onChange={(event) => setUsername(event.target.value)}
              autoComplete="username"
              className="dark-input mt-1 w-full px-3 py-2"
              placeholder="请输入用户名"
              required
            />
          </label>

          {isRegister && (
            <>
              <label className="block">
                <span className="text-sm font-medium text-slate-700 dark:text-slate-300">邮箱</span>
                <input
                  type="email"
                  value={email}
                  onChange={(event) => setEmail(event.target.value)}
                  autoComplete="email"
                  className="dark-input mt-1 w-full px-3 py-2"
                  placeholder="请输入邮箱"
                  required
                />
              </label>

              <label className="block">
                <span className="text-sm font-medium text-slate-700 dark:text-slate-300">显示名称</span>
                <input
                  value={displayName}
                  onChange={(event) => setDisplayName(event.target.value)}
                  autoComplete="name"
                  className="dark-input mt-1 w-full px-3 py-2"
                  placeholder="可留空"
                />
              </label>
            </>
          )}

          <label className="block">
            <span className="text-sm font-medium text-slate-700 dark:text-slate-300">密码</span>
            <input
              type="password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              autoComplete={isRegister ? 'new-password' : 'current-password'}
              minLength={6}
              className="dark-input mt-1 w-full px-3 py-2"
              placeholder={isRegister ? '至少 6 位' : '请输入密码'}
              required
            />
          </label>

          {error && (
            <p className="text-sm text-red-500">{error}</p>
          )}

          <button
            type="submit"
            disabled={loading}
            className="w-full inline-flex items-center justify-center gap-2 rounded-lg btn-primary px-4 py-2.5 text-sm font-medium disabled:cursor-not-allowed disabled:opacity-70"
          >
            <LoadingButtonContent
              loading={loading}
              loadingText={isRegister ? '注册并登录' : '登录'}
            >
              {isRegister ? (
                <UserPlus className="w-4 h-4" />
              ) : (
                <LogIn className="w-4 h-4" />
              )}
              {isRegister ? '注册并登录' : '登录'}
            </LoadingButtonContent>
          </button>
        </form>
      </div>
    </div>
  );
}
