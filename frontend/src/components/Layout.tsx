import {Link, Outlet, useLocation, useNavigate} from 'react-router-dom';
import {
  BookOpen,
  BrainCircuit,
  CalendarDays,
  ChevronRight,
  Database,
  GitBranch,
  History,
  Home,
  LogIn,
  LogOut,
  Menu,
  MessageSquareText,
  Radar,
  Moon,
  Sun,
  UserRound,
  X,
} from 'lucide-react';
import {useTheme} from '../hooks/useTheme';
import {useEffect, useState} from 'react';
import MyModelOnboarding from './MyModelOnboarding';
import {authApi} from '../api/auth';
import {useAuthStore} from '../stores/authStore';

interface NavItem {
  id: string;
  path: string;
  label: string;
  icon: React.ComponentType<{ className?: string }>;
  description?: string;
}

interface NavGroup {
  id: string;
  title: string;
  items: NavItem[];
}

const APP_NAME = 'AI 面试平台';

export function resolveDocumentTitle(pathname: string): string {
  if (pathname === '/dashboard' || pathname === '/') return `工作台 · ${APP_NAME}`;
  if (pathname === '/recruitment') return `招聘雷达 · ${APP_NAME}`;
  if (pathname === '/resources') return `求职资源 · ${APP_NAME}`;
  if (pathname === '/profile') return `我的资料 · ${APP_NAME}`;
  if (pathname === '/login') return `登录 · ${APP_NAME}`;
  if (pathname === '/upload') return `上传简历 · ${APP_NAME}`;
  if (pathname.startsWith('/history/')) return `简历详情 · ${APP_NAME}`;
  if (pathname === '/history') return `简历管理 · ${APP_NAME}`;
  if (pathname === '/interview-hub') return `模拟面试 · ${APP_NAME}`;
  if (pathname.startsWith('/interviews/')) return `面试报告 · ${APP_NAME}`;
  if (pathname === '/interviews') return `面试记录 · ${APP_NAME}`;
  if (pathname === '/interview-schedule') return `面试日程 · ${APP_NAME}`;
  if (pathname.startsWith('/interview')) return `模拟面试 · ${APP_NAME}`;
  if (pathname === '/knowledgebase/upload') return `上传文档 · ${APP_NAME}`;
  if (pathname === '/knowledgebase/chat') return `问答助手 · ${APP_NAME}`;
  if (pathname === '/knowledgebase') return `知识库 · ${APP_NAME}`;
  if (pathname === '/agent-trace') return `出题过程回放 · ${APP_NAME}`;
  if (pathname === '/rag-traces') return `问答过程回放 · ${APP_NAME}`;
  if (pathname === '/eval') return `RAG 效果评测 · ${APP_NAME}`;
  if (pathname === '/settings') return `设置 · ${APP_NAME}`;
  return APP_NAME;
}

export default function Layout() {
  const location = useLocation();
  const currentPath = location.pathname;
  const {theme, toggleTheme} = useTheme();
  const navigate = useNavigate();
  const user = useAuthStore();
  const [mobileNavOpen, setMobileNavOpen] = useState(false);

  useEffect(() => {
    document.title = resolveDocumentTitle(currentPath);
    setMobileNavOpen(false);
  }, [currentPath]);

  useEffect(() => {
    if (!mobileNavOpen) {
      return;
    }
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = previousOverflow;
    };
  }, [mobileNavOpen]);

  const handleLogout = () => {
    authApi.logout();
    navigate('/login');
  };

  const openInterviewModalWithResume = (resumeId: number) => {
    navigate(`/interview/${resumeId}`);
  };

  // 主叙事前置：意图问答 / Agent Trace / 评测；求职外围保留但后置。
  const navGroups: NavGroup[] = [
    {
      id: 'engine',
      title: '知识引擎',
      items: [
        { id: 'kb-chat', path: '/knowledgebase/chat', label: '知识库问答', icon: MessageSquareText, description: '意图 + RAG' },
        { id: 'agent-trace', path: '/agent-trace', label: '出题过程回放', icon: GitBranch, description: 'Chat / Tool span 树' },
        { id: 'rag-traces', path: '/rag-traces', label: '问答过程回放', icon: GitBranch, description: '一次提问怎么走的' },
        { id: 'eval', path: '/eval', label: 'RAG 评测', icon: BrainCircuit, description: '标准五指标' },
        { id: 'kb-manage', path: '/knowledgebase', label: '知识库管理', icon: Database },
      ],
    },
    {
      id: 'primary',
      title: '面试训练',
      items: [
        { id: 'dashboard', path: '/dashboard', label: '工作台', icon: Home },
        { id: 'interview', path: '/interview', label: '模拟面试', icon: MessageSquareText },
        { id: 'records', path: '/interviews', label: '面试记录', icon: History },
      ],
    },
    {
      id: 'peripheral',
      title: '求职工具',
      items: [
        { id: 'recruitment', path: '/recruitment', label: '招聘雷达', icon: Radar },
        { id: 'resources', path: '/resources', label: '求职资源', icon: BookOpen },
        { id: 'schedule', path: '/interview-schedule', label: '面试日程', icon: CalendarDays },
        { id: 'profile', path: '/profile', label: '我的资料', icon: UserRound },
      ],
    },
  ];

  // 判断当前页面是否匹配导航项
  const isActive = (path: string) => {
    if (path.startsWith('#')) return false;
    if (path === '/dashboard') {
      return currentPath === '/' || currentPath === '/dashboard';
    }
    if (path === '/knowledgebase/chat') {
      return currentPath === '/knowledgebase/chat';
    }
    if (path === '/knowledgebase') {
      return currentPath === '/knowledgebase' || currentPath === '/knowledgebase/upload';
    }
    if (path === '/eval') {
      return currentPath === '/eval' || currentPath.startsWith('/eval/');
    }
    if (path === '/agent-trace') {
      return currentPath === '/agent-trace' || currentPath.startsWith('/agent-trace/');
    }
    if (path === '/rag-traces') {
      return currentPath === '/rag-traces' || currentPath.startsWith('/rag-traces/');
    }
    if (path === '/interview') {
      return currentPath === '/interview-hub'
        || currentPath === '/interview'
        || currentPath.startsWith('/interview/');
    }
    if (path === '/profile') {
      return currentPath === '/profile'
        || currentPath === '/history'
        || currentPath.startsWith('/history/')
        || currentPath === '/upload'
        || currentPath === '/settings';
    }
    return currentPath.startsWith(path);
  };

  return (
    <div className="flex min-h-screen bg-stone-100 dark:bg-stone-950">
      <header className="fixed inset-x-0 top-0 z-30 flex h-14 items-center justify-between border-b border-stone-200 bg-white px-4 dark:border-stone-800 dark:bg-stone-950 md:hidden">
        <Link to="/dashboard" className="flex items-center gap-2.5 min-w-0">
          <img
            src="/bear-doctor-logo.png"
            alt=""
            className="w-9 h-9 rounded-full object-cover shrink-0 ring-1 ring-stone-200/80 dark:ring-stone-700"
          />
          <span className="truncate text-sm font-semibold text-stone-900 dark:text-stone-50">AI 面试平台</span>
        </Link>
        <button
          type="button"
          aria-label="打开导航菜单"
          aria-controls="app-sidebar"
          aria-expanded={mobileNavOpen}
          onClick={() => setMobileNavOpen(true)}
          className="inline-flex h-10 w-10 items-center justify-center rounded-lg text-stone-600 hover:bg-stone-100 dark:text-stone-300 dark:hover:bg-stone-900"
        >
          <Menu className="h-5 w-5" />
        </button>
      </header>

      {mobileNavOpen && (
        <button
          type="button"
          aria-label="关闭导航菜单"
          onClick={() => setMobileNavOpen(false)}
          className="fixed inset-0 z-40 bg-black/35 md:hidden"
        />
      )}

      {/* 左侧边栏 */}
      <aside
        id="app-sidebar"
        className={`fixed left-0 top-0 z-50 flex h-dvh w-60 flex-col border-r border-stone-200 bg-white transition-transform duration-200 dark:border-stone-800 dark:bg-stone-950 md:h-screen md:translate-x-0 ${
          mobileNavOpen ? 'translate-x-0' : '-translate-x-full'
        }`}
      >
        {/* Logo */}
        <div className="flex h-16 items-center gap-3 border-b border-stone-200 px-5 dark:border-stone-800">
          <Link to="/dashboard" className="flex flex-1 min-w-0 items-center gap-3 group">
            <img
              src="/bear-doctor-logo.png"
              alt="AI 面试平台"
              className="w-10 h-10 rounded-full object-cover shrink-0 ring-1 ring-stone-200/80 dark:ring-stone-700"
            />
            <span className="truncate text-base font-semibold text-stone-900 dark:text-stone-50 tracking-tight">AI 面试平台</span>
          </Link>
          <button
            type="button"
            aria-label="关闭导航菜单"
            onClick={() => setMobileNavOpen(false)}
            className="inline-flex h-9 w-9 shrink-0 items-center justify-center rounded-lg text-stone-500 hover:bg-stone-100 dark:text-stone-400 dark:hover:bg-stone-900 md:hidden"
          >
            <X className="h-5 w-5" />
          </button>
        </div>

        {/* 主题切换按钮 */}
        <div className="px-3 pb-1 pt-3">
          <button
            onClick={toggleTheme}
            className="flex w-full items-center gap-2 rounded-lg px-3 py-2 text-sm text-stone-500 transition-colors hover:bg-stone-100 hover:text-stone-800 dark:text-stone-400 dark:hover:bg-stone-900 dark:hover:text-stone-200"
          >
            {theme === 'dark' ? (
              <>
                <Sun className="w-4 h-4" />
                <span className="font-medium">浅色模式</span>
              </>
            ) : (
              <>
                <Moon className="w-4 h-4" />
                <span className="font-medium">深色模式</span>
              </>
            )}
          </button>
        </div>

        {/* 导航菜单 */}
        <nav className="flex-1 overflow-y-auto p-3">
          <div className="space-y-5">
            {navGroups.map((group) => (
              <div key={group.id}>
                <div className="px-3 mb-1.5">
                  <span className="text-[11px] font-medium text-stone-400 dark:text-stone-500 uppercase tracking-wider">
                    {group.title}
                  </span>
                </div>
                <div className="space-y-0.5">
                  {group.items.map((item) => {
                    const active = isActive(item.path);

                    return (
                      <Link
                        key={item.id}
                        to={item.path}
                        onClick={() => setMobileNavOpen(false)}
                        className={`group relative flex items-center gap-2.5 rounded-lg px-3 py-2.5 transition-colors duration-150
                          ${active
                            ? 'bg-primary-50 text-primary-800 dark:bg-primary-950/50 dark:text-primary-300'
                            : 'text-stone-600 hover:bg-stone-100 hover:text-stone-900 dark:text-stone-400 dark:hover:bg-stone-900 dark:hover:text-stone-100'
                          }`}
                      >
                        <item.icon className={`w-[18px] h-[18px] shrink-0 ${active ? 'text-primary-600 dark:text-primary-400' : ''}`} />
                        <div className="flex-1 min-w-0">
                          <span className={`text-sm block leading-tight ${active ? 'font-semibold' : 'font-medium'}`}>
                            {item.label}
                          </span>
                        </div>
                        {active && <ChevronRight className="w-3.5 h-3.5 text-primary-400 shrink-0" />}
                      </Link>
                    );
                  })}
                </div>
              </div>
            ))}
          </div>
        </nav>

        {/* 底部信息 */}
        <div className="border-t border-stone-200 p-3 dark:border-stone-800">
          {user ? (
            <div className="mb-2 rounded-lg bg-stone-50 p-3 dark:bg-stone-900">
              <div className="mb-2">
                <Link to="/profile" className="block rounded-lg hover:bg-stone-100 dark:hover:bg-stone-800 px-1 py-1 -mx-1">
                  <p className="text-sm font-medium text-stone-800 dark:text-stone-100 truncate">
                    {user.displayName || user.username}
                  </p>
                  <p className="text-xs text-stone-400 dark:text-stone-500 truncate">
                    @{user.username}
                  </p>
                </Link>
              </div>
              <button
                type="button"
                onClick={handleLogout}
                className="w-full inline-flex items-center justify-center gap-2 rounded-lg px-3 py-2 text-sm font-medium text-stone-600 dark:text-stone-300 hover:bg-stone-100 dark:hover:bg-stone-800"
              >
                <LogOut className="w-4 h-4" />
                退出登录
              </button>
            </div>
          ) : (
            <button
              type="button"
              onClick={() => {
                setMobileNavOpen(false);
                navigate('/login');
              }}
              className="mb-2 w-full inline-flex items-center justify-center gap-2 rounded-lg btn-primary px-3 py-2 text-sm font-medium"
            >
              <LogIn className="w-4 h-4" />
              登录 / 注册
            </button>
          )}
          <p className="px-1 text-[11px] text-stone-400 dark:text-stone-600">v1.0</p>
        </div>
      </aside>

      {/* 主内容区 */}
      <main className="min-h-screen w-full min-w-0 flex-1 overflow-y-auto px-4 pb-8 pt-18 md:ml-60 md:px-7 md:pb-10 md:pt-7 lg:px-9">
        <div>
          <Outlet context={{ openInterviewModalWithResume }} />
        </div>
      </main>

      {/* 模型配置全局引导：首登向导 + 未配置访问凭证时的提示 */}
      <MyModelOnboarding user={user} />
    </div>
  );
}
