import {Link, Outlet, useLocation, useNavigate} from 'react-router-dom';
import {motion} from 'framer-motion';
import {Calendar, ChevronRight, Database, FileStack, FlaskConical, LogIn, LogOut, Menu, MessageSquare, Mic2, Moon, Network, Settings, Sun, Users, X,} from 'lucide-react';
import {useTheme} from '../hooks/useTheme';
import {useEffect, useState} from 'react';
import UnifiedInterviewModal, {UnifiedInterviewConfig} from './UnifiedInterviewModal';
import MyModelOnboarding from './MyModelOnboarding';
import {authApi} from '../api/auth';
import {AUTH_CHANGED_EVENT, getStoredUser, StoredUser} from '../api/authStorage';

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

const APP_NAME = 'AI面试平台';

function resolveDocumentTitle(pathname: string): string {
  if (pathname === '/login') return `登录 · ${APP_NAME}`;
  if (pathname === '/upload') return `上传简历 · ${APP_NAME}`;
  if (pathname.startsWith('/history/')) return `简历详情 · ${APP_NAME}`;
  if (pathname === '/history' || pathname === '/') return `简历管理 · ${APP_NAME}`;
  if (pathname === '/interview-hub') return `模拟面试 · ${APP_NAME}`;
  if (pathname.startsWith('/interviews/')) return `面试报告 · ${APP_NAME}`;
  if (pathname === '/interviews') return `面试记录 · ${APP_NAME}`;
  if (pathname.startsWith('/voice-interview')) return `语音面试 · ${APP_NAME}`;
  if (pathname.startsWith('/interview')) return `模拟面试 · ${APP_NAME}`;
  if (pathname === '/interview-schedule') return `面试日程 · ${APP_NAME}`;
  if (pathname === '/knowledgebase/upload') return `上传文档 · ${APP_NAME}`;
  if (pathname === '/knowledgebase/chat') return `问答助手 · ${APP_NAME}`;
  if (pathname === '/knowledgebase') return `知识库 · ${APP_NAME}`;
  if (pathname === '/knowledge-graph') return `知识图谱 · ${APP_NAME}`;
  if (pathname === '/eval') return `统一评测 · ${APP_NAME}`;
  if (pathname === '/settings') return `设置 · ${APP_NAME}`;
  return APP_NAME;
}

export default function Layout() {
  const location = useLocation();
  const currentPath = location.pathname;
  const {theme, toggleTheme} = useTheme();
  const navigate = useNavigate();
  const [user, setUser] = useState<StoredUser | null>(() => getStoredUser());
  const [mobileNavOpen, setMobileNavOpen] = useState(false);
  const [interviewModalPreset, setInterviewModalPreset] = useState<{
    defaultMode: 'text' | 'voice';
    defaultResumeId?: number;
    title: string;
    subtitle: string;
    startButtonText: string;
  } | null>(null);

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

  useEffect(() => {
    const syncUser = () => setUser(getStoredUser());
    window.addEventListener(AUTH_CHANGED_EVENT, syncUser);
    window.addEventListener('storage', syncUser);
    return () => {
      window.removeEventListener(AUTH_CHANGED_EVENT, syncUser);
      window.removeEventListener('storage', syncUser);
    };
  }, []);

  const handleLogout = () => {
    authApi.logout();
    navigate('/login');
  };

  const openInterviewModalWithResume = (resumeId: number) => {
    setInterviewModalPreset({
      defaultMode: 'text',
      defaultResumeId: resumeId,
      title: '开始模拟面试',
      subtitle: '配置面试参数，开始练习',
      startButtonText: '开始面试',
    });
  };

  const handleInterviewStart = (config: UnifiedInterviewConfig) => {
    setInterviewModalPreset(null);
    if (config.mode === 'text') {
      navigate('/interview', {
        state: {
          resumeId: config.resumeId,
          interviewConfig: {
            skillId: config.skillId,
            difficulty: config.difficulty,
            questionCount: config.questionCount,
            llmProvider: config.llmProvider,
            customCategories: config.customCategories,
            jdText: config.customJdText,
            knowledgeBaseIds: config.knowledgeBaseIds,
          },
        },
      });
      return;
    }

    const params = new URLSearchParams({
      skillId: config.skillId,
      difficulty: config.difficulty,
    });
    navigate(`/voice-interview?${params.toString()}`, {
      state: {
        voiceConfig: {
          skillId: config.skillId,
          difficulty: config.difficulty,
          techEnabled: true,
          projectEnabled: true,
          hrEnabled: true,
          plannedDuration: config.plannedDuration,
          resumeId: config.resumeId,
          llmProvider: config.llmProvider,
        },
      },
    });
  };

  // 按业务模块组织的导航项
  const navGroups: NavGroup[] = [
    {
      id: 'interview',
      title: '面试准备',
      items: [
        { id: 'resumes', path: '/history', label: '简历管理', icon: FileStack, description: '管理简历，AI 分析' },
        { id: 'interview-hub', path: '/interview-hub', label: '模拟面试', icon: Mic2, description: '文字/语音面试练习' },
        { id: 'interviews', path: '/interviews', label: '面试记录', icon: Users, description: '查看面试历史' },
        { id: 'interview-schedule', path: '/interview-schedule', label: '面试日程', icon: Calendar, description: '管理面试安排' },
      ],
    },
    {
      id: 'knowledge',
      title: '知识库',
      items: [
        { id: 'kb-manage', path: '/knowledgebase', label: '知识库管理', icon: Database, description: '管理知识文档' },
        { id: 'chat', path: '/knowledgebase/chat', label: '问答助手', icon: MessageSquare, description: '基于知识库问答' },
        { id: 'knowledge-graph', path: '/knowledge-graph', label: '知识图谱', icon: Network, description: '技能/知识点关系图谱' },
        { id: 'eval', path: '/eval', label: '统一评测', icon: FlaskConical, description: '意图/RAG/裁判评测与回归' },
      ],
    },
    {
      id: 'system',
      title: '系统',
      items: [
        { id: 'settings', path: '/settings', label: '设置', icon: Settings, description: '管理模型和语音服务' },
      ],
    },
  ];

  // 判断当前页面是否匹配导航项
  const isActive = (path: string) => {
    if (path.startsWith('#')) return false;
    if (path === '/history') {
      return currentPath === '/history'
        || currentPath === '/'
        || currentPath.startsWith('/history/')
        || currentPath === '/upload';
    }
    if (path === '/interview-hub') {
      return currentPath === '/interview-hub'
        || currentPath === '/interview'
        || currentPath.startsWith('/interview/')
        || currentPath.startsWith('/voice-interview');
    }
    if (path === '/knowledgebase') {
      return currentPath === '/knowledgebase' || currentPath === '/knowledgebase/upload';
    }
    return currentPath.startsWith(path);
  };

  return (
    <div className="flex min-h-screen">
      <header className="fixed inset-x-0 top-0 z-30 h-16 px-4 flex items-center justify-between border-b border-stone-200/80 bg-white/90 backdrop-blur-xl dark:border-stone-800 dark:bg-stone-950/90 md:hidden">
        <Link to="/history" className="flex items-center gap-2.5 min-w-0">
          <img
            src="/bear-doctor-logo.png"
            alt=""
            className="w-9 h-9 rounded-full object-cover shrink-0 ring-1 ring-stone-200/80 dark:ring-stone-700"
          />
          <span className="truncate text-sm font-semibold text-stone-900 dark:text-stone-50">AI面试平台</span>
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
          className="fixed inset-0 z-40 bg-black/35 backdrop-blur-[1px] md:hidden"
        />
      )}

      {/* 左侧边栏 */}
      <aside
        id="app-sidebar"
        className={`w-64 bg-white/95 dark:bg-stone-950/95 md:bg-white/60 md:dark:bg-stone-950/60 backdrop-blur-2xl border-r border-white/40 dark:border-white/10 fixed h-dvh md:h-screen left-0 top-0 z-50 flex flex-col shadow-[4px_0_24px_rgba(0,0,0,0.08)] md:shadow-[4px_0_24px_rgba(0,0,0,0.02)] transition-transform duration-300 md:translate-x-0 ${
          mobileNavOpen ? 'translate-x-0' : '-translate-x-full'
        }`}
      >
        {/* Logo */}
        <div className="p-5 border-b border-stone-200/80 dark:border-stone-800 flex items-center gap-3">
          <Link to="/history" className="flex flex-1 min-w-0 items-center gap-3 group">
            <img
              src="/bear-doctor-logo.png"
              alt="AI面试平台"
              className="w-10 h-10 rounded-full object-cover shrink-0 ring-1 ring-stone-200/80 dark:ring-stone-700"
            />
            <span className="truncate text-base font-semibold text-stone-900 dark:text-stone-50 tracking-tight">AI面试平台</span>
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
        <div className="px-4 pt-3 pb-1">
          <button
            onClick={toggleTheme}
            className="w-full flex items-center justify-center gap-2 px-3 py-2 rounded-lg bg-stone-100 dark:bg-stone-900 text-stone-600 dark:text-stone-300 hover:bg-stone-200 dark:hover:bg-stone-800 transition-colors text-sm"
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
        <nav className="flex-1 p-3 overflow-y-auto">
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
                        className={`group relative flex items-center gap-2.5 px-3 py-2 rounded-xl transition-all duration-300
                          ${active
                            ? 'bg-primary-500/10 text-primary-700 dark:text-primary-300 shadow-sm border border-primary-500/20'
                            : 'text-stone-600 dark:text-stone-400 hover:bg-white/60 dark:hover:bg-stone-900/50 hover:text-stone-900 dark:hover:text-stone-100 hover:shadow-sm'
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
        <div className="p-4 border-t border-stone-200/80 dark:border-stone-800">
          {user ? (
            <div className="mb-2 rounded-xl bg-stone-50 dark:bg-stone-900 p-3">
              <div className="mb-2">
                <p className="text-sm font-medium text-stone-800 dark:text-stone-100 truncate">
                  {user.displayName || user.username}
                </p>
                <p className="text-xs text-stone-400 dark:text-stone-500 truncate">
                  @{user.username}
                </p>
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
      <main className="flex-1 min-w-0 w-full p-4 pt-20 md:ml-64 md:p-10 min-h-screen overflow-y-auto">
        <motion.div
          key={currentPath}
          initial={{ opacity: 0, y: 12 }}
          animate={{ opacity: 1, y: 0 }}
          exit={{ opacity: 0, y: -12 }}
          transition={{ duration: 0.25 }}
        >
          <Outlet context={{ openInterviewModalWithResume }} />
        </motion.div>
      </main>

      {/* 统一面试弹窗 */}
      <UnifiedInterviewModal
        isOpen={interviewModalPreset !== null}
        onClose={() => setInterviewModalPreset(null)}
        onStart={handleInterviewStart}
        defaultMode={interviewModalPreset?.defaultMode || 'text'}
        defaultResumeId={interviewModalPreset?.defaultResumeId}
        hideModeSwitch={interviewModalPreset?.defaultResumeId == null}
        title={interviewModalPreset?.title || '开始模拟面试'}
        subtitle={interviewModalPreset?.subtitle || '选择面试模式和主题，快速开始'}
        startButtonText={interviewModalPreset?.startButtonText || '开始面试'}
      />

      {/* BYOK 全局引导：首登两步向导 + 未配置 Key 的全局提示 */}
      <MyModelOnboarding user={user} />
    </div>
  );
}
