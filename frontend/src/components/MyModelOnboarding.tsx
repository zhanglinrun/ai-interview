import { useCallback, useEffect, useRef, useState } from 'react';
import { ArrowLeft, ArrowRight, Database, KeyRound, ShieldCheck, Sparkles, X } from 'lucide-react';
import { userLlmProviderApi } from '../api/userLlmProvider';
import { USER_LLM_NOT_CONFIGURED_EVENT } from '../api/request';
import { getStoredUser, type StoredUser } from '../api/authStorage';
import type { MyProviderDTO } from '../types/userLlmProvider';
import MyModelForm from './MyModelForm';
import ConfirmDialog from './ConfirmDialog';

interface MyModelOnboardingProps {
  user: StoredUser | null;
}

type WizardMode = 'onboarding' | 'edit';

/**
 * BYOK 全局引导：
 * - 登录后拉取 `/api/llm-provider/mine`，未配置（configured=false）则弹出两步向导；
 * - 监听全局 7006 事件（任意 chat 入口因未配置 Key 被拒），弹出「去配置」提示并可直达向导；
 * - 是否再次弹出完全由后端 `configured` 派生，不引入额外 onboarded 标记。
 */
export default function MyModelOnboarding({ user }: MyModelOnboardingProps) {
  const userId = user?.userId;

  const [initial, setInitial] = useState<MyProviderDTO | null>(null);
  const [wizardOpen, setWizardOpen] = useState(false);
  const [wizardMode, setWizardMode] = useState<WizardMode>('onboarding');
  const [step, setStep] = useState<1 | 2>(1);
  const [formKey, setFormKey] = useState(0);
  const [promptOpen, setPromptOpen] = useState(false);

  const wizardOpenRef = useRef(false);
  useEffect(() => {
    wizardOpenRef.current = wizardOpen;
  }, [wizardOpen]);

  const closeWizard = useCallback(() => setWizardOpen(false), []);

  // 登录态变化时检查是否需要引导（每个登录用户只在 userId 变化时检查一次）
  useEffect(() => {
    if (!userId) {
      setInitial(null);
      setWizardOpen(false);
      setPromptOpen(false);
      return;
    }
    let cancelled = false;
    userLlmProviderApi
      .getMine()
      .then((dto) => {
        if (cancelled) {
          return;
        }
        setInitial(dto);
        if (!dto.configured && window.location.pathname !== '/login') {
          setWizardMode('onboarding');
          setStep(1);
          setFormKey((k) => k + 1);
          setWizardOpen(true);
        }
      })
      .catch(() => {
        // 拉取失败不阻断浏览（例如网络问题）；使用 AI 功能时仍有 7006 兜底
      });
    return () => {
      cancelled = true;
    };
  }, [userId]);

  // 监听全局「未配置模型 Key」事件（覆盖 RAG 问答、出题与评估等 chat 入口）
  useEffect(() => {
    const handler = () => {
      if (!getStoredUser()) {
        return;
      }
      if (wizardOpenRef.current) {
        return;
      }
      setPromptOpen(true);
    };
    window.addEventListener(USER_LLM_NOT_CONFIGURED_EVENT, handler);
    return () => window.removeEventListener(USER_LLM_NOT_CONFIGURED_EVENT, handler);
  }, []);

  const openWizardFromPrompt = useCallback(async () => {
    setPromptOpen(false);
    try {
      const dto = await userLlmProviderApi.getMine();
      setInitial(dto);
    } catch {
      // 保留上次已知配置
    }
    setWizardMode('edit');
    setStep(2);
    setFormKey((k) => k + 1);
    setWizardOpen(true);
  }, []);

  const handleCompleted = useCallback(async () => {
    try {
      const dto = await userLlmProviderApi.getMine();
      setInitial(dto);
    } catch {
      // ignore
    }
    setWizardOpen(false);
  }, []);

  const title = step === 1 ? '配置你的模型 Key' : '填写你的模型';
  const subtitle =
    step === 1
      ? '使用你自己的模型服务（BYOK）'
      : '兼容 OpenAI 接口的服务都可以，保存前可先测试';

  return (
    <>
        {wizardOpen && (
          <>
            <div
              className="fixed inset-0 z-[70] bg-black/50"
            />
            <div className="fixed inset-0 z-[70] flex items-center justify-center p-4">
              <div
                className="surface-card w-full max-w-lg p-6 max-h-[88vh] overflow-y-auto"
              >
                {/* 头部 */}
                <div className="mb-5 flex items-start justify-between gap-3">
                  <div className="flex items-center gap-3">
                    <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-primary-50 text-primary-700 dark:bg-primary-950/50 dark:text-primary-300">
                      {step === 1 ? (
                        <Sparkles className="h-5 w-5" />
                      ) : (
                        <KeyRound className="h-5 w-5" />
                      )}
                    </div>
                    <div>
                      <h3 className="text-lg font-bold text-slate-900 dark:text-white">{title}</h3>
                      <p className="text-xs text-slate-500 dark:text-slate-400 mt-0.5">{subtitle}</p>
                    </div>
                  </div>
                  <button
                    type="button"
                    onClick={closeWizard}
                    className="text-slate-400 hover:text-slate-600 dark:hover:text-slate-300 transition-colors"
                    title="关闭"
                  >
                    <X className="h-5 w-5" />
                  </button>
                </div>

                {/* 步骤指示 */}
                <div className="mb-5 flex items-center gap-2">
                  {[1, 2].map((s) => (
                    <div
                      key={s}
                      className={`h-1.5 flex-1 rounded-full transition-colors ${
                        s <= step ? 'bg-primary-500' : 'bg-slate-200 dark:bg-slate-700'
                      }`}
                    />
                  ))}
                </div>

                {step === 1 ? (
                  <div className="space-y-4">
                    <p className="text-sm text-slate-600 dark:text-slate-300 leading-relaxed">
                      面试提问、回答评估和知识问答使用你自己的模型 API Key，调用记录按账号保存，费用由你的模型账号承担。
                    </p>
                    <ul className="space-y-2.5">
                      <li className="flex items-start gap-2.5 text-sm text-slate-600 dark:text-slate-300">
                        <Sparkles className="mt-0.5 h-4 w-4 flex-shrink-0 text-primary-500" />
                        <span>
                          任意兼容 OpenAI 的服务都可用：通义千问 DashScope、DeepSeek、Kimi、智谱 GLM 等的 Base URL。
                        </span>
                      </li>
                      <li className="flex items-start gap-2.5 text-sm text-slate-600 dark:text-slate-300">
                        <ShieldCheck className="mt-0.5 h-4 w-4 flex-shrink-0 text-primary-500" />
                        <span>Key 仅用于你的模型调用，加密保存，页面不会再次显示完整内容。</span>
                      </li>
                      <li className="flex items-start gap-2.5 text-sm text-slate-600 dark:text-slate-300">
                        <Database className="mt-0.5 h-4 w-4 flex-shrink-0 text-primary-500" />
                        <span>
                          文档向量化由平台统一处理；没有配置 Key 时仍可浏览页面和上传资料。
                        </span>
                      </li>
                    </ul>
                    <p className="text-xs text-slate-400">
                      可以先跳过，需要调用模型时会再次提醒。
                    </p>

                    <div className="flex items-center justify-between gap-3 pt-2">
                      <button
                        type="button"
                        onClick={closeWizard}
                        className="px-4 py-2.5 rounded-xl text-sm font-medium text-slate-500 dark:text-slate-400
                          hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors"
                      >
                        跳过
                      </button>
                      <button
                        type="button"
                        onClick={() => setStep(2)}
                        className="btn-primary inline-flex items-center gap-2 px-5 py-2.5 text-sm"
                      >
                        开始配置
                        <ArrowRight className="h-4 w-4" />
                      </button>
                    </div>
                  </div>
                ) : (
                  <div className="space-y-3">
                    {wizardMode === 'onboarding' && (
                      <button
                        type="button"
                        onClick={() => setStep(1)}
                        className="inline-flex items-center gap-1.5 text-xs font-medium text-slate-500
                          dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200 transition-colors"
                      >
                        <ArrowLeft className="h-3.5 w-3.5" />
                        上一步
                      </button>
                    )}
                    <MyModelForm
                      key={formKey}
                      initial={initial}
                      saveLabel="完成"
                      savingLabel="保存中..."
                      onSaved={handleCompleted}
                    />
                  </div>
                )}
              </div>
            </div>
          </>
        )}
      <ConfirmDialog
        open={promptOpen}
        title="需要配置模型 Key"
        message="请先在设置里配置你的模型 Key，配置后即可使用 RAG 问答、出题、面试评估等 AI 功能。"
        confirmText="去配置"
        cancelText="以后再说"
        onConfirm={openWizardFromPrompt}
        onCancel={() => setPromptOpen(false)}
      />
    </>
  );
}
