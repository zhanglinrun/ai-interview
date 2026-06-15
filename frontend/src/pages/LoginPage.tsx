import { FormEvent, useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Loader2, LogIn, Sparkles, UserPlus } from 'lucide-react';
import { authApi } from '../api/auth';
import { getStoredUser } from '../api/authStorage';

type AuthMode = 'login' | 'register';

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

  const from = searchParams.get('from') || '/history';
  const isRegister = mode === 'register';

  useEffect(() => {
    if (getStoredUser()) {
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
      setError(err instanceof Error ? err.message : '操作失败，请重试');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-[calc(100vh-5rem)] flex items-center justify-center">
      <div className="w-full max-w-sm rounded-2xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-900 p-7 shadow-sm">
        <div className="flex items-center gap-3 mb-6">
          <div className="w-10 h-10 bg-primary-500 rounded-xl flex items-center justify-center text-white">
            <Sparkles className="w-5 h-5" />
          </div>
          <div>
            <h1 className="text-lg font-semibold text-slate-900 dark:text-white">
              {isRegister ? '注册' : '登录'}
            </h1>
            <p className="text-sm text-slate-500 dark:text-slate-400">进入 AI Interview</p>
          </div>
        </div>

        <div className="mb-5 grid grid-cols-2 rounded-lg bg-slate-100 dark:bg-slate-800 p-1">
          {(['login', 'register'] as AuthMode[]).map((item) => (
            <button
              key={item}
              type="button"
              onClick={() => {
                setMode(item);
                setError('');
              }}
              className={`rounded-md px-3 py-2 text-sm font-medium transition-colors ${
                mode === item
                  ? 'bg-white dark:bg-slate-950 text-primary-600 shadow-sm'
                  : 'text-slate-500 dark:text-slate-400 hover:text-slate-800 dark:hover:text-white'
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
              className="mt-1 w-full rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-950 px-3 py-2 text-slate-900 dark:text-white outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-500/20"
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
                  className="mt-1 w-full rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-950 px-3 py-2 text-slate-900 dark:text-white outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-500/20"
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
                  className="mt-1 w-full rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-950 px-3 py-2 text-slate-900 dark:text-white outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-500/20"
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
              className="mt-1 w-full rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-950 px-3 py-2 text-slate-900 dark:text-white outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-500/20"
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
            className="w-full inline-flex items-center justify-center gap-2 rounded-lg bg-primary-500 px-4 py-2.5 text-sm font-semibold text-white hover:bg-primary-600 disabled:cursor-not-allowed disabled:opacity-70"
          >
            {loading ? (
              <Loader2 className="w-4 h-4 animate-spin" />
            ) : isRegister ? (
              <UserPlus className="w-4 h-4" />
            ) : (
              <LogIn className="w-4 h-4" />
            )}
            {isRegister ? '注册并登录' : '登录'}
          </button>
        </form>
      </div>
    </div>
  );
}
