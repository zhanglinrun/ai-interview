import { useCallback, useEffect, useRef, useState } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
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

  // 监听全局「未配置模型 Key」事件（覆盖 RAG 问答/出题/评估/语音等所有 chat 入口）
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
      ? '本 Demo 使用 BYOK（自带模型 Key）'
      : '兼容 OpenAI 的服务都可以，填完可先测试连通';

  return (
    <>
      <AnimatePresence>
        {wizardOpen && (
          <>
            <motion.div
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              className="fixed inset-0 bg-black/50 backdrop-blur-sm z-[70]"
            />
            <div className="fixed inset-0 z-[70] flex items-center justify-center p-4">
              <motion.div
                initial={{ opacity: 0, scale: 0.95, y: 20 }}
                animate={{ opacity: 1, scale: 1, y: 0 }}
                exit={{ opacity: 0, scale: 0.95, y: 20 }}
                className="surface-card w-full max-w-lg p-6 max-h-[88vh] overflow-y-auto"
              >
                {/* 头部 */}
                <div className="mb-5 flex items-start justify-between gap-3">
                  <div className="flex items-center gap-3">
                    <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-gradient-to-r from-primary-500 to-primary-600 shadow-lg shadow-primary-500/25">
                      {step === 1 ? (
                        <Sparkles className="h-5 w-5 text-white" />
                      ) : (
                        <KeyRound className="h-5 w-5 text-white" />
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
                      为控制在线 Demo 成本，AI 功能采用 <span className="font-semibold">BYOK（Bring Your Own Key）</span>：
                      你使用<span className="font-semibold">自己的</span>模型 API Key，数据按账号隔离，成本自付。
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
                        <span>Key 仅用于你自己的 AI 调用（问答 / 出题 / 面试评估），加密存储、永不明文回显。</span>
                      </li>
                      <li className="flex items-start gap-2.5 text-sm text-slate-600 dark:text-slate-300">
                        <Database className="mt-0.5 h-4 w-4 flex-shrink-0 text-primary-500" />
                        <span>
                          向量化（Embedding）由平台统一承担：未配置也能上传 / 向量化知识库，但无法进行问答 / 出题 / 评估。
                        </span>
                      </li>
                    </ul>
                    <p className="text-xs text-slate-400">
                      可以先「跳过」浏览产品，使用 AI 功能时会再次提醒你配置。
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
                      <motion.button
                        type="button"
                        onClick={() => setStep(2)}
                        whileHover={{ scale: 1.02 }}
                        whileTap={{ scale: 0.98 }}
                        className="inline-flex items-center gap-2 px-5 py-2.5 rounded-xl font-semibold text-sm
                          text-white bg-gradient-to-r from-primary-500 to-primary-600 shadow-lg shadow-primary-500/25
                          hover:from-primary-600 hover:to-primary-700 transition-all"
                      >
                        开始配置
                        <ArrowRight className="h-4 w-4" />
                      </motion.button>
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
              </motion.div>
            </div>
          </>
        )}
      </AnimatePresence>

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
