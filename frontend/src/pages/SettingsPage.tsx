import { useState, useEffect, useCallback, useMemo } from 'react';
import type { ReactNode } from 'react';
import {
  Plus, Trash2, Plug, CheckCircle, XCircle,
  Eye, EyeOff, RefreshCw, Server, Edit2, ChevronDown, Database, KeyRound, Lock,
} from 'lucide-react';
import { llmProviderApi } from '../api/llmProvider';
import { userLlmProviderApi } from '../api/userLlmProvider';
import { isAdmin } from '../api/authStorage';
import { getErrorMessage, ApiError } from '../api/request';
import ConfirmDialog from '../components/ConfirmDialog';
import LoadingButtonContent from '../components/LoadingButtonContent';
import MyModelForm, {formatTemperatureInput} from '../components/MyModelForm';
import { EmptyState, LoadingState } from '../components/PageState';
import PageHeader from '../components/ui/PageHeader';
import type {
  ProviderItem, CreateProviderRequest, UpdateProviderRequest,
  ProviderTestResult,
} from '../types/llmProvider';
import type { MyProviderDTO } from '../types/userLlmProvider';

const DEFAULT_EMBEDDING_DIMENSIONS = 1024;

// Provider 预设：已知 Provider 的 Base URL、推荐模型和向量模型
const PROVIDER_PRESETS: Record<string, {
  baseUrl: string;
  models: { value: string; label: string }[];
  embeddingModels?: { value: string; label: string }[];
  embeddingDimensions?: number;
  supportsEmbedding: boolean;
}> = {
  dashscope: {
    baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
    models: [
      { value: 'qwen3.6-flash', label: 'Qwen3.6 Flash' },
      { value: 'qwen3.5-plus', label: 'Qwen3.5 Plus' },
      { value: 'qwen3.5-flash', label: 'Qwen3.5 Flash' },
      { value: 'qwen3-max', label: 'Qwen3 Max' },
      { value: 'qwen-max', label: 'Qwen Max' },
      { value: 'qwen-plus', label: 'Qwen Plus' },
      { value: 'qwen-flash', label: 'Qwen Flash' },
      { value: 'qwq-32b', label: 'QwQ-32B' },
    ],
    embeddingModels: [
      { value: 'text-embedding-v3', label: 'text-embedding-v3 — 推荐' },
    ],
    embeddingDimensions: DEFAULT_EMBEDDING_DIMENSIONS,
    supportsEmbedding: true,
  },
  deepseek: {
    baseUrl: 'https://api.deepseek.com',
    models: [
      { value: 'deepseek-v4-flash', label: 'DeepSeek V4 Flash' },
      { value: 'deepseek-v4-pro', label: 'DeepSeek V4 Pro' },
      { value: 'deepseek-chat', label: 'DeepSeek V3.2' },
      { value: 'deepseek-reasoner', label: 'DeepSeek R1' },
    ],
    supportsEmbedding: false,
  },
  glm: {
    baseUrl: 'https://open.bigmodel.cn/api/coding/paas/v4',
    models: [
      { value: 'glm-5.1', label: 'GLM-5.1' },
      { value: 'glm-5', label: 'GLM-5' },
      { value: 'glm-4.7', label: 'GLM-4.7' },
      { value: 'glm-4.7-flash', label: 'GLM-4.7 Flash' },
      { value: 'glm-4.6', label: 'GLM-4.6' },
      { value: 'glm-4-plus', label: 'GLM-4 Plus' },
      { value: 'glm-4-air-250414', label: 'GLM-4 Air' },
      { value: 'glm-4-flash-250414', label: 'GLM-4 Flash' },
    ],
    embeddingModels: [
      { value: 'embedding-3', label: 'embedding-3 — 推荐' },
    ],
    embeddingDimensions: DEFAULT_EMBEDDING_DIMENSIONS,
    supportsEmbedding: true,
  },
  kimi: {
    baseUrl: 'https://api.moonshot.cn/v1',
    models: [
      { value: 'kimi-k2.6', label: 'Kimi K2.6' },
      { value: 'kimi-k2.5', label: 'Kimi K2.5' },
      { value: 'kimi-k2', label: 'Kimi K2' },
      { value: 'kimi-k2-thinking', label: 'Kimi K2 Thinking' },
      { value: 'kimi-latest', label: 'kimi-latest' },
    ],
    supportsEmbedding: false,
  },
};

type ConfigRowProps = {
  label: string;
  value: ReactNode;
  title?: string;
  monospace?: boolean;
  emphasis?: boolean;
};

type StatusBadgeProps = {
  icon: ReactNode;
  children: ReactNode;
};

type TestButtonContentProps = {
  loading: boolean;
};

const CARD_CLASS = `flex h-full flex-col rounded-lg border border-slate-200
  bg-white p-4 dark:border-slate-700 dark:bg-slate-800`;

const ICON_WRAP_CLASS = `flex h-9 w-9 flex-shrink-0 items-center justify-center rounded-lg
  bg-primary-50 text-primary-600 dark:bg-primary-900/30 dark:text-primary-300`;

const DETAILS_CLASS = `mb-4 flex-1 space-y-1 rounded-lg border border-slate-100 bg-slate-50/70
  p-3 dark:border-slate-700/80 dark:bg-slate-900/30`;

const ACTION_BAR_CLASS = `mt-auto flex min-h-12 flex-wrap items-center gap-2 border-t
  border-slate-100 pt-3 dark:border-slate-700`;

const ACTION_BUTTON_CLASS = `inline-flex h-8 items-center gap-1.5 rounded-lg px-3 text-xs
  font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-50`;

const REQUIRED_FIELDS_MESSAGE = '请填写必填字段';
const REQUIRED_EMBEDDING_MODEL_MESSAGE =
  '支持向量化时需要填写向量模型，例如 GLM 填 embedding-3';
const INVALID_EMBEDDING_DIMENSIONS_MESSAGE =
  `向量维度必须为正整数，当前 ES 向量索引为 ${DEFAULT_EMBEDDING_DIMENSIONS} 维`;

type ProviderFormMode = 'create' | 'update';

type ProviderFormValues = {
  id: string;
  baseUrl: string;
  apiKey: string;
  model: string;
  embeddingModel: string;
  embeddingDimensions: string;
  supportsEmbedding: boolean;
  temperature: string;
};

type NormalizedProviderForm = {
  id: string;
  baseUrl: string;
  apiKey: string;
  model: string;
  embeddingModel: string;
  embeddingDimensions: number;
  supportsEmbedding: boolean;
  temperature?: number;
};

type ProviderFormResult =
  | { ok: true; values: NormalizedProviderForm }
  | { ok: false; message: string };

function StatusBadge({ icon, children }: StatusBadgeProps) {
  return (
    <span className="inline-flex h-6 items-center gap-1.5 rounded-md bg-primary-50 px-2.5 text-xs font-semibold text-primary-700 dark:bg-primary-900/30 dark:text-primary-300">
      {icon}
      {children}
    </span>
  );
}

function TestButtonContent({ loading }: TestButtonContentProps) {
  return (
    <LoadingButtonContent
      loading={loading}
      loadingText="测试"
      className="inline-flex items-center gap-1.5"
      spinnerClassName="w-3.5 h-3.5 animate-spin"
    >
      <span className="inline-flex items-center gap-1.5">
        <RefreshCw className="w-3.5 h-3.5" />
        测试
      </span>
    </LoadingButtonContent>
  );
}

function ConfigRow({ label, value, title, monospace = false, emphasis = false }: ConfigRowProps) {
  return (
    <div
      className={`grid grid-cols-[108px_minmax(0,1fr)] items-start gap-3 rounded-md px-2 py-2 text-xs ${
        emphasis ? 'bg-white ring-1 ring-slate-100 dark:bg-slate-800/80 dark:ring-slate-700' : ''
      }`}
    >
      <dt className="whitespace-nowrap text-slate-500 dark:text-slate-400">{label}</dt>
      <dd
        className={`min-w-0 truncate text-right font-medium text-slate-700 dark:text-slate-200 ${
          monospace ? 'font-mono' : ''
        }`}
        title={title}
      >
        {value}
      </dd>
    </div>
  );
}

function normalizeProviderForm(values: ProviderFormValues, mode: ProviderFormMode): ProviderFormResult {
  const id = values.id.trim();
  const baseUrl = values.baseUrl.trim();
  const apiKey = values.apiKey.trim();
  const model = values.model.trim();
  const embeddingModel = values.embeddingModel.trim();
  const supportsEmbedding = values.supportsEmbedding;
  const embeddingDimensions = Number.parseInt(values.embeddingDimensions.trim(), 10);
  const temperatureText = values.temperature.trim();
  const temperature = temperatureText ? Number.parseFloat(temperatureText) : undefined;

  if (!baseUrl || !model || (mode === 'create' && (!id || !apiKey))) {
    return { ok: false, message: REQUIRED_FIELDS_MESSAGE };
  }

  if (supportsEmbedding && !embeddingModel) {
    return { ok: false, message: REQUIRED_EMBEDDING_MODEL_MESSAGE };
  }

  if (
    supportsEmbedding
    && (!Number.isFinite(embeddingDimensions) || embeddingDimensions <= 0)
  ) {
    return { ok: false, message: INVALID_EMBEDDING_DIMENSIONS_MESSAGE };
  }

  return {
    ok: true,
    values: {
      id,
      baseUrl,
      apiKey,
      model,
      embeddingModel,
      embeddingDimensions,
      supportsEmbedding,
      ...(
        temperature !== undefined && !Number.isNaN(temperature)
          ? { temperature }
          : {}
      ),
    },
  };
}

function buildCreateProviderRequest(values: NormalizedProviderForm): CreateProviderRequest {
  const data: CreateProviderRequest = {
    id: values.id,
    baseUrl: values.baseUrl,
    apiKey: values.apiKey,
    model: values.model,
    supportsEmbedding: values.supportsEmbedding,
  };

  if (values.supportsEmbedding) {
    data.embeddingModel = values.embeddingModel;
    data.embeddingDimensions = values.embeddingDimensions;
  }

  if (values.temperature !== undefined) {
    data.temperature = values.temperature;
  }

  return data;
}

function buildUpdateProviderRequest(values: NormalizedProviderForm): UpdateProviderRequest {
  const data: UpdateProviderRequest = {
    baseUrl: values.baseUrl,
    model: values.model,
    embeddingModel: values.supportsEmbedding ? values.embeddingModel : '',
    supportsEmbedding: values.supportsEmbedding,
  };

  if (values.supportsEmbedding) {
    data.embeddingDimensions = values.embeddingDimensions;
  }

  if (values.apiKey) {
    data.apiKey = values.apiKey;
  }

  if (values.temperature !== undefined) {
    data.temperature = values.temperature;
  }

  return data;
}

export default function SettingsPage() {
  const [providers, setProviders] = useState<ProviderItem[]>([]);
  const [defaultProviderId, setDefaultProviderId] = useState('');
  const [defaultEmbeddingProviderId, setDefaultEmbeddingProviderId] = useState('');
  const [loading, setLoading] = useState(true);
  const [reloading, setReloading] = useState(false);
  // 管理员配置区（全局 Provider）仅管理员可见：普通用户只用「我的模型」BYOK
  const [isAdminUser] = useState(() => isAdmin());
  // 兜底：即便角色判定为管理员，若后端仍返回 403/401 也隐藏管理员配置区
  const [adminForbidden, setAdminForbidden] = useState(false);

  // 「我的模型」（BYOK，当前登录用户）
  const [myProvider, setMyProvider] = useState<MyProviderDTO | null>(null);
  const [myProviderLoading, setMyProviderLoading] = useState(true);

  // Modal state
  const [showModal, setShowModal] = useState(false);
  const [editingProvider, setEditingProvider] = useState<ProviderItem | null>(null);
  const [saving, setSaving] = useState(false);

  // Form fields
  const [formId, setFormId] = useState('');
  const [formBaseUrl, setFormBaseUrl] = useState('');
  const [formApiKey, setFormApiKey] = useState('');
  const [formModel, setFormModel] = useState('');
  const [formEmbeddingModel, setFormEmbeddingModel] = useState('');
  const [formEmbeddingDimensions, setFormEmbeddingDimensions] = useState(
    String(DEFAULT_EMBEDDING_DIMENSIONS),
  );
  const [formSupportsEmbedding, setFormSupportsEmbedding] = useState(false);
  const [formTemperature, setFormTemperature] = useState('');
  const [showApiKey, setShowApiKey] = useState(false);
  const [showModelDropdown, setShowModelDropdown] = useState(false);
  const [showEmbeddingDropdown, setShowEmbeddingDropdown] = useState(false);

  // 当前表单 Provider ID 匹配的预设
  const currentPreset = useMemo(
    () => PROVIDER_PRESETS[formId.toLowerCase()],
    [formId],
  );

  // Test state
  const [testingId, setTestingId] = useState<string | null>(null);
  const [testResults, setTestResults] = useState<Record<string, ProviderTestResult>>({});

  // Delete confirmation
  const [deleteConfirmId, setDeleteConfirmId] = useState<string | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [pendingDefaultProviderId, setPendingDefaultProviderId] = useState<string | null>(null);
  const [pendingDefaultEmbeddingProviderId, setPendingDefaultEmbeddingProviderId] = useState<string | null>(null);
  const [settingDefault, setSettingDefault] = useState(false);
  const [settingEmbeddingDefault, setSettingEmbeddingDefault] = useState(false);

  const pendingEmbeddingProvider = useMemo(
    () => providers.find(provider => provider.id === pendingDefaultEmbeddingProviderId) ?? null,
    [pendingDefaultEmbeddingProviderId, providers],
  );

  // Toast notification
  const [toast, setToast] = useState<{ message: string; type: 'success' | 'error' } | null>(null);

  const showToast = useCallback((message: string, type: 'success' | 'error' = 'success') => {
    setToast({ message, type });
    setTimeout(() => setToast(null), 3000);
  }, []);

  const isGlobalDefaultProvider = useCallback((providerId: string) => (
    defaultProviderId === providerId
  ), [defaultProviderId]);

  const isDefaultEmbeddingProvider = useCallback((providerId: string) => (
    defaultEmbeddingProviderId === providerId
  ), [defaultEmbeddingProviderId]);

  const loadData = useCallback(async () => {
    try {
      const [providerList, defaultProvider] = await Promise.all([
        llmProviderApi.list(),
        llmProviderApi.getDefaultProvider(),
      ]);
      setProviders(providerList);
      setDefaultProviderId(defaultProvider.defaultProvider);
      setDefaultEmbeddingProviderId(defaultProvider.defaultEmbeddingProvider);
      setAdminForbidden(false);
    } catch (err) {
      // 普通用户无管理员权限时静默隐藏管理员配置区，仅保留「我的模型」
      if (err instanceof ApiError && (err.code === 403 || err.code === 401)) {
        setAdminForbidden(true);
      } else {
        console.error('Failed to load settings:', err);
        showToast(getErrorMessage(err, '加载数据失败'), 'error');
      }
    } finally {
      setLoading(false);
    }
  }, [showToast]);

  const loadMyProvider = useCallback(async () => {
    try {
      const dto = await userLlmProviderApi.getMine();
      setMyProvider(dto);
    } catch (err) {
      console.error('Failed to load my model:', err);
    } finally {
      setMyProviderLoading(false);
    }
  }, []);

  const handleReloadProviders = async () => {
    try {
      setReloading(true);
      await llmProviderApi.reload();
      await loadData();
      showToast('模型配置已重新加载');
    } catch (err) {
      showToast(getErrorMessage(err, '重新加载失败'), 'error');
    } finally {
      setReloading(false);
    }
  };

  useEffect(() => {
    // 普通用户不拉取管理员配置，避免无谓请求与信息暴露
    if (isAdminUser) {
      loadData();
    } else {
      setLoading(false);
    }
    loadMyProvider();
  }, [isAdminUser, loadData, loadMyProvider]);

  // --- Modal helpers ---
  const openCreateModal = () => {
    setEditingProvider(null);
    setFormId('');
    setFormBaseUrl('');
    setFormApiKey('');
    setFormModel('');
    setFormEmbeddingModel('');
    setFormEmbeddingDimensions(String(DEFAULT_EMBEDDING_DIMENSIONS));
    setFormSupportsEmbedding(false);
    setFormTemperature('');
    setShowApiKey(false);
    setShowModal(true);
  };

  const openEditModal = (provider: ProviderItem) => {
    setEditingProvider(provider);
    setFormId(provider.id);
    setFormBaseUrl(provider.baseUrl);
    setFormApiKey('');
    setFormModel(provider.model);
    setFormEmbeddingModel(provider.embeddingModel || '');
    setFormEmbeddingDimensions(
      provider.embeddingDimensions != null
        ? String(provider.embeddingDimensions)
        : String(DEFAULT_EMBEDDING_DIMENSIONS),
    );
    setFormSupportsEmbedding(provider.supportsEmbedding);
    setFormTemperature(formatTemperatureInput(provider.temperature));
    setShowApiKey(false);
    setShowModal(true);
  };

  const closeModal = () => {
    setShowModal(false);
    setEditingProvider(null);
  };

  // --- CRUD handlers ---
  const handleCreate = async () => {
    const formResult = normalizeProviderForm({
      id: formId,
      baseUrl: formBaseUrl,
      apiKey: formApiKey,
      model: formModel,
      embeddingModel: formEmbeddingModel,
      embeddingDimensions: formEmbeddingDimensions,
      supportsEmbedding: formSupportsEmbedding,
      temperature: formTemperature,
    }, 'create');

    if (!formResult.ok) {
      showToast(formResult.message, 'error');
      return;
    }

    setSaving(true);
    try {
      await llmProviderApi.create(buildCreateProviderRequest(formResult.values));
      showToast('模型服务已添加');
      closeModal();
      await loadData();
    } catch (err) {
      console.error('Failed to create provider:', err);
      showToast(getErrorMessage(err, '创建失败'), 'error');
    } finally {
      setSaving(false);
    }
  };

  const handleUpdate = async () => {
    if (!editingProvider) return;
    const formResult = normalizeProviderForm({
      id: formId,
      baseUrl: formBaseUrl,
      apiKey: formApiKey,
      model: formModel,
      embeddingModel: formEmbeddingModel,
      embeddingDimensions: formEmbeddingDimensions,
      supportsEmbedding: formSupportsEmbedding,
      temperature: formTemperature,
    }, 'update');

    if (!formResult.ok) {
      showToast(formResult.message, 'error');
      return;
    }

    setSaving(true);
    try {
      await llmProviderApi.update(
        editingProvider.id,
        buildUpdateProviderRequest(formResult.values),
      );
      showToast('模型服务已更新');
      closeModal();
      await loadData();
    } catch (err) {
      console.error('Failed to update provider:', err);
      showToast(getErrorMessage(err, '更新失败'), 'error');
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async () => {
    if (!deleteConfirmId) return;
    setDeleting(true);
    try {
      await llmProviderApi.delete(deleteConfirmId);
      showToast('模型服务已删除');
      setDeleteConfirmId(null);
      await loadData();
    } catch (err) {
      console.error('Failed to delete provider:', err);
      showToast(getErrorMessage(err, '删除失败'), 'error');
    } finally {
      setDeleting(false);
    }
  };

  const handleTest = async (id: string) => {
    setTestingId(id);
    setTestResults(prev => {
      const next = { ...prev };
      delete next[id];
      return next;
    });
    try {
      const result = await llmProviderApi.test(id);
      setTestResults(prev => ({ ...prev, [id]: result }));
    } catch (err) {
      console.error('Test failed:', err);
      setTestResults(prev => ({
        ...prev,
        [id]: {
          success: false,
          message: getErrorMessage(err, '连接测试失败'),
          model: '',
        },
      }));
    } finally {
      setTestingId(null);
    }
  };

  const handleSetDefault = async (providerId: string) => {
    setPendingDefaultProviderId(providerId);
  };

  const handleConfirmSetDefault = async () => {
    if (!pendingDefaultProviderId) {
      return;
    }
    setSettingDefault(true);
    try {
      await llmProviderApi.updateDefaultProvider({
        defaultProvider: pendingDefaultProviderId,
        defaultEmbeddingProvider: defaultEmbeddingProviderId,
      });
      showToast(`已将 "${pendingDefaultProviderId}" 设为默认聊天服务`);
      setPendingDefaultProviderId(null);
      await loadData();
    } catch (err) {
      console.error('Failed to set default:', err);
      showToast(getErrorMessage(err, '设置默认聊天服务失败'), 'error');
    } finally {
      setSettingDefault(false);
    }
  };

  const handleSetEmbeddingDefault = async (provider: ProviderItem) => {
    if (!provider.supportsEmbedding || !provider.embeddingModel) {
      showToast('该模型服务不支持向量化，不能用于处理知识库', 'error');
      return;
    }
    setPendingDefaultEmbeddingProviderId(provider.id);
  };

  const handleConfirmSetEmbeddingDefault = async () => {
    if (!pendingDefaultEmbeddingProviderId) {
      return;
    }
    setSettingEmbeddingDefault(true);
    try {
      await llmProviderApi.updateDefaultEmbeddingProvider({
        defaultProvider: defaultProviderId,
        defaultEmbeddingProvider: pendingDefaultEmbeddingProviderId,
      });
      showToast(`已将 "${pendingDefaultEmbeddingProviderId}" 的 ${pendingEmbeddingProvider?.embeddingModel ?? '向量模型'} (${pendingEmbeddingProvider?.embeddingDimensions ?? DEFAULT_EMBEDDING_DIMENSIONS}维) 设为默认向量服务`);
      setPendingDefaultEmbeddingProviderId(null);
      await loadData();
    } catch (err) {
      console.error('Failed to set embedding default:', err);
      showToast(getErrorMessage(err, '设置默认向量服务失败'), 'error');
    } finally {
      setSettingEmbeddingDefault(false);
    }
  };

  const handleSaveModal = () => {
    if (editingProvider) {
      handleUpdate();
    } else {
      handleCreate();
    }
  };

  // --- Render ---
  return (
    <div className="max-w-4xl mx-auto">
      <PageHeader
        eyebrow="个人设置"
        title="模型设置"
        description="配置用于知识问答和模拟面试的模型服务。"
      />

      {/* 我的模型（BYOK，当前登录用户自带 Key） */}
      <div className="mb-6">
        <div className="mb-4 flex items-center gap-3">
          <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-primary-50 text-primary-600 dark:bg-primary-900/30 dark:text-primary-300">
            <KeyRound className="h-5 w-5" />
          </div>
          <div>
            <h2 className="text-lg font-bold text-slate-800 dark:text-white">我的模型</h2>
            <p className="mt-0.5 text-xs text-slate-500 dark:text-slate-400">
              填写自己的模型 API Key，用于知识问答、面试出题和评估；文档向量化由平台处理。
            </p>
          </div>
        </div>
        <div className="surface-card p-5">
          {myProviderLoading ? (
            <LoadingState />
          ) : (
            <MyModelForm
              initial={myProvider}
              showDelete
              onToast={showToast}
              onSaved={loadMyProvider}
              onDeleted={loadMyProvider}
            />
          )}
        </div>
      </div>

      {/* 管理员配置区：仅管理员可见 */}
      {(!isAdminUser || adminForbidden) ? (
        <div className="surface-card flex items-start gap-3 p-4 text-sm text-slate-500 dark:text-slate-400">
          <Lock className="mt-0.5 h-4 w-4 flex-shrink-0" />
          <span>
            平台模型由管理员维护。你只需要在上方填写自己的 Key，即可使用知识问答和模拟面试。
          </span>
        </div>
      ) : loading ? (
        <LoadingState />
      ) : (
        <div>
              {/* Provider header */}
              <div className="flex items-center justify-between mb-4">
                <h2 className="text-lg font-bold text-slate-800 dark:text-white">
                  模型服务
                </h2>
                <div className="flex items-center gap-2">
                  <button
                    onClick={handleReloadProviders}
                    disabled={reloading}
                    className="btn-secondary flex items-center gap-2 px-4 py-2 text-sm disabled:opacity-50"
                  >
                    <LoadingButtonContent loading={reloading} loadingText="加载中">
                      <span className="inline-flex items-center gap-2">
                        <RefreshCw className="w-4 h-4" />
                        重新加载
                      </span>
                    </LoadingButtonContent>
                  </button>
                  <button
                    onClick={openCreateModal}
                    className="btn-primary flex items-center gap-2 px-4 py-2 text-sm"
                  >
                    <Plus className="w-4 h-4" />
                    添加服务
                  </button>
                </div>
              </div>

              {/* Provider grid */}
              {providers.length === 0 ? (
                <EmptyState
                  className="surface-card text-center py-12"
                  icon={Server}
                  iconClassName="w-12 h-12 text-slate-300 dark:text-slate-600 mx-auto mb-3"
                  title="还没有模型服务"
                  titleClassName="text-slate-500 dark:text-slate-400 text-sm"
                />
              ) : (
                <div className="grid grid-cols-1 items-stretch gap-4 md:grid-cols-2">
                  {providers.map((provider) => {
                    const isGlobalDefault = isGlobalDefaultProvider(provider.id);
                    const isEmbeddingDefault = isDefaultEmbeddingProvider(provider.id);
                    const canUseEmbedding = provider.supportsEmbedding && !!provider.embeddingModel;

                    return (
                    <div
                      key={provider.id}
                      className={CARD_CLASS}
                    >
                      {/* Card header */}
                      <div className="mb-4 flex items-start justify-between gap-3">
                        <div className="flex min-w-0 items-center gap-3">
                          <div className={ICON_WRAP_CLASS}>
                            <Server className="h-4 w-4" />
                          </div>
                          <div className="min-w-0">
                            <h3 className="truncate text-sm font-semibold text-slate-800 dark:text-white">
                              {provider.id}
                            </h3>
                            <p className="mt-1 text-xs text-slate-500 dark:text-slate-400">聊天与向量模型</p>
                          </div>
                        </div>
                        <div className="flex flex-col items-end gap-1">
                          {isGlobalDefault && (
                            <StatusBadge icon={<Plug className="h-3 w-3" />}>文字默认</StatusBadge>
                          )}
                          {isEmbeddingDefault && (
                            <StatusBadge icon={<Database className="h-3 w-3" />}>向量默认</StatusBadge>
                          )}
                        </div>
                      </div>

                      {/* Card details */}
                      <dl className={DETAILS_CLASS}>
                        <ConfigRow label="Base URL" value={provider.baseUrl} title={provider.baseUrl} emphasis />
                        <ConfigRow label="聊天模型" value={provider.model} title={provider.model} emphasis />
                        <ConfigRow
                          label="向量模型"
                          value={canUseEmbedding ? '支持' : '不支持'}
                          title={canUseEmbedding ? provider.embeddingModel ?? '' : '不能用于知识库向量化'}
                        />
                        {provider.embeddingModel && (
                          <ConfigRow label="实际向量" value={provider.embeddingModel} title={provider.embeddingModel} emphasis={isEmbeddingDefault} />
                        )}
                        {canUseEmbedding && (
                          <ConfigRow label="向量维度" value={`${provider.embeddingDimensions ?? DEFAULT_EMBEDDING_DIMENSIONS} 维`} emphasis={isEmbeddingDefault} />
                        )}
                        {provider.temperature != null && (
                          <ConfigRow label="温度" value={formatTemperatureInput(provider.temperature)} />
                        )}
                        <ConfigRow
                          label="API Key"
                          value={provider.maskedApiKey}
                          title={provider.maskedApiKey}
                          monospace
                          emphasis
                        />
                      </dl>

                      {/* Test result */}
                      {testResults[provider.id] && (
                        <div
                          className={`mb-3 px-3 py-2 rounded-lg text-xs font-medium ${
                            testResults[provider.id].success
                              ? 'bg-emerald-50 dark:bg-emerald-900/20 text-emerald-700 dark:text-emerald-300'
                              : 'bg-red-50 dark:bg-red-900/20 text-red-700 dark:text-red-300'
                          }`}
                        >
                          <div className="flex items-center gap-1.5">
                            {testResults[provider.id].success
                              ? <CheckCircle className="w-3.5 h-3.5 flex-shrink-0" />
                              : <XCircle className="w-3.5 h-3.5 flex-shrink-0" />
                            }
                            <span>{testResults[provider.id].message}</span>
                          </div>
                        </div>
                      )}

                      {/* Card actions */}
                      <div className={ACTION_BAR_CLASS}>
                        <button
                          onClick={() => openEditModal(provider)}
                          className={`${ACTION_BUTTON_CLASS} text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-700`}
                          title="编辑"
                        >
                          <Edit2 className="w-3.5 h-3.5" />
                          编辑
                        </button>
                        <button
                          onClick={() => handleTest(provider.id)}
                          disabled={testingId === provider.id}
                          className={`${ACTION_BUTTON_CLASS} text-blue-600 hover:bg-blue-50 dark:text-blue-400 dark:hover:bg-blue-900/20`}
                          title="测试连接"
                        >
                          <TestButtonContent loading={testingId === provider.id} />
                        </button>
                        <button
                          onClick={() => handleSetDefault(provider.id)}
                          disabled={isGlobalDefault || settingDefault}
                          className={`${ACTION_BUTTON_CLASS} text-primary-600 hover:bg-primary-50 dark:text-primary-400 dark:hover:bg-primary-900/20 disabled:hover:bg-transparent dark:disabled:hover:bg-transparent`}
                          title="设为默认文字服务"
                        >
                          <Plug className="w-3.5 h-3.5" />
                          设为文字
                        </button>
                        <button
                          onClick={() => handleSetEmbeddingDefault(provider)}
                          disabled={!canUseEmbedding || isEmbeddingDefault || settingEmbeddingDefault}
                          className={`${ACTION_BUTTON_CLASS} text-emerald-600 hover:bg-emerald-50 dark:text-emerald-400 dark:hover:bg-emerald-900/20 disabled:hover:bg-transparent dark:disabled:hover:bg-transparent`}
                          title={canUseEmbedding ? '设为默认向量服务' : '该模型服务不支持向量化'}
                        >
                          <Database className="w-3.5 h-3.5" />
                          设为向量
                        </button>
                        <button
                          onClick={() => setDeleteConfirmId(provider.id)}
                          className={`${ACTION_BUTTON_CLASS} ml-auto text-slate-400 hover:bg-red-50 hover:text-red-500 dark:text-slate-500 dark:hover:bg-red-900/20 dark:hover:text-red-300`}
                          title="删除"
                        >
                          <Trash2 className="w-3.5 h-3.5" />
                        </button>
                      </div>
                    </div>
                    );
                  })}
                </div>
              )}

        </div>
      )}

      {/* Create / Edit Modal */}
      {showModal && (
          <>
            <div
              onClick={closeModal}
              className="fixed inset-0 bg-black/50 z-50"
            />
            <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
              <div
                onClick={(e) => e.stopPropagation()}
                className="surface-card max-w-lg w-full p-5"
              >
                <h3 className="text-xl font-bold text-slate-900 dark:text-white mb-5">
                  {editingProvider ? '编辑模型服务' : '添加模型服务'}
                </h3>

                <div className="space-y-4">
                  {/* Provider ID */}
                  <div>
                    <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">
                      服务标识 <span className="text-red-500">*</span>
                    </label>
                    <input
                      type="text"
                      value={formId}
                      onChange={(e) => {
                        const newId = e.target.value;
                        setFormId(newId);
                        // 新建时自动填充已知 Provider 的 Base URL
                        if (!editingProvider) {
                          const preset = PROVIDER_PRESETS[newId.toLowerCase()];
                          if (preset) {
                            setFormBaseUrl(preset.baseUrl);
                            setFormSupportsEmbedding(preset.supportsEmbedding);
                            setFormEmbeddingModel(preset.embeddingModels?.[0]?.value ?? '');
                            setFormEmbeddingDimensions(
                              String(preset.embeddingDimensions ?? DEFAULT_EMBEDDING_DIMENSIONS),
                            );
                          }
                        }
                      }}
                      disabled={!!editingProvider}
                      placeholder="例如: dashscope, deepseek, glm, kimi"
                      className="dark-input w-full px-3 py-2.5 text-sm disabled:opacity-50 disabled:cursor-not-allowed"
                    />
                  </div>

                  {/* Base URL */}
                  <div>
                    <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">
                      Base URL <span className="text-red-500">*</span>
                    </label>
                    <input
                      type="text"
                      value={formBaseUrl}
                      onChange={(e) => setFormBaseUrl(e.target.value)}
                      placeholder="例如: https://api.openai.com/v1"
                      className="dark-input w-full px-3 py-2.5 text-sm"
                    />
                  </div>

                  {/* API Key */}
                  <div>
                    <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">
                      API Key{' '}
                      {editingProvider && (
                        <span className="text-slate-400 font-normal">(留空则不修改)</span>
                      )}
                      {!editingProvider && <span className="text-red-500">*</span>}
                    </label>
                    <div className="relative">
                      <input
                        type={showApiKey ? 'text' : 'password'}
                        value={formApiKey}
                        onChange={(e) => setFormApiKey(e.target.value)}
                        placeholder={editingProvider ? '留空则保持原值' : '输入 API Key'}
                        className="dark-input w-full px-3 py-2.5 pr-10 text-sm"
                      />
                      <button
                        type="button"
                        onClick={() => setShowApiKey(!showApiKey)}
                        className="absolute right-3 top-1/2 -translate-y-1/2 text-slate-400
                          hover:text-slate-600 dark:hover:text-slate-300 transition-colors"
                      >
                        {showApiKey ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                      </button>
                    </div>
                  </div>

                  {/* Chat Model */}
                  <div>
                    <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">
                      聊天模型 <span className="text-red-500">*</span>
                    </label>
                    <div className="relative">
                      <input
                        type="text"
                        value={formModel}
                        onChange={(e) => {
                          setFormModel(e.target.value);
                          setShowModelDropdown(false);
                        }}
                        onFocus={() => currentPreset && setShowModelDropdown(true)}
                        onBlur={() => setTimeout(() => setShowModelDropdown(false), 150)}
                        placeholder={currentPreset ? '从下拉列表选择或输入自定义聊天模型名' : '例如: qwen3.5-flash, deepseek-v4-flash, glm-5'}
                        className="dark-input w-full px-3 py-2.5 text-sm"
                      />
                      {currentPreset && (
                        <button
                          type="button"
                          onClick={() => setShowModelDropdown(!showModelDropdown)}
                          className="absolute right-3 top-1/2 -translate-y-1/2 text-slate-400
                            hover:text-slate-600 dark:hover:text-slate-300 transition-colors"
                        >
                          <ChevronDown className="w-4 h-4" />
                        </button>
                      )}
                      {showModelDropdown && currentPreset && (
                        <div className="absolute z-10 mt-1 w-full bg-white dark:bg-slate-700
                          border border-slate-200 dark:border-slate-600 rounded-lg shadow-lg
                          max-h-60 overflow-auto">
                          {currentPreset.models.map((m) => (
                            <button
                              key={m.value}
                              type="button"
                              onClick={() => {
                                setFormModel(m.value);
                                setShowModelDropdown(false);
                              }}
                              className={`w-full px-4 py-2.5 text-left text-sm hover:bg-primary-50
                                dark:hover:bg-slate-600 transition-colors flex justify-between items-center
                                ${formModel === m.value
                                  ? 'text-primary-600 dark:text-primary-400 font-medium bg-primary-50 dark:bg-slate-600'
                                  : 'text-slate-700 dark:text-slate-200'}`}
                            >
                              <span className="font-mono">{m.value}</span>
                              <span className="text-xs text-slate-400 dark:text-slate-500 ml-2 whitespace-nowrap">{m.label}</span>
                            </button>
                          ))}
                        </div>
                      )}
                    </div>
                  </div>

                  {/* Embedding Model */}
                  <div>
                    <div className="mb-1.5 flex items-center justify-between gap-3">
                      <label className="block text-sm font-medium text-slate-700 dark:text-slate-300">
                        向量模型 <span className="text-slate-400 font-normal">(知识库向量化，例如 GLM 填 embedding-3)</span>
                      </label>
                      <label className="inline-flex items-center gap-2 text-xs font-medium text-slate-600 dark:text-slate-300">
                        <input
                          type="checkbox"
                          checked={formSupportsEmbedding}
                          onChange={(e) => {
                            setFormSupportsEmbedding(e.target.checked);
                            if (!e.target.checked) {
                              setFormEmbeddingModel('');
                              setFormEmbeddingDimensions(String(DEFAULT_EMBEDDING_DIMENSIONS));
                            }
                          }}
                          className="h-4 w-4 rounded border-slate-300 text-primary-600 focus:ring-primary-500"
                        />
                        支持 Embedding
                      </label>
                    </div>
                    <div className="relative">
                      <input
                        type="text"
                        value={formEmbeddingModel}
                        onChange={(e) => {
                          setFormEmbeddingModel(e.target.value);
                          setShowEmbeddingDropdown(false);
                        }}
                        onFocus={() => formSupportsEmbedding && currentPreset?.embeddingModels && setShowEmbeddingDropdown(true)}
                        onBlur={() => setTimeout(() => setShowEmbeddingDropdown(false), 150)}
                        disabled={!formSupportsEmbedding}
                        placeholder={formSupportsEmbedding
                          ? (currentPreset?.embeddingModels ? '从下拉列表选择或输入自定义向量模型名' : '例如: text-embedding-v3, embedding-3')
                          : '该服务不提供向量模型'}
                        className="dark-input w-full px-3 py-2.5 text-sm disabled:cursor-not-allowed disabled:opacity-60"
                      />
                      {formSupportsEmbedding && currentPreset?.embeddingModels && (
                        <button
                          type="button"
                          onClick={() => setShowEmbeddingDropdown(!showEmbeddingDropdown)}
                          className="absolute right-3 top-1/2 -translate-y-1/2 text-slate-400
                            hover:text-slate-600 dark:hover:text-slate-300 transition-colors"
                        >
                          <ChevronDown className="w-4 h-4" />
                        </button>
                      )}
                      {formSupportsEmbedding && showEmbeddingDropdown && currentPreset?.embeddingModels && (
                        <div className="absolute z-10 mt-1 w-full bg-white dark:bg-slate-700
                          border border-slate-200 dark:border-slate-600 rounded-lg shadow-lg
                          max-h-60 overflow-auto">
                          {currentPreset.embeddingModels.map((m) => (
                            <button
                              key={m.value}
                              type="button"
                              onClick={() => {
                                setFormEmbeddingModel(m.value);
                                setShowEmbeddingDropdown(false);
                              }}
                              className={`w-full px-4 py-2.5 text-left text-sm hover:bg-primary-50
                                dark:hover:bg-slate-600 transition-colors flex justify-between items-center
                                ${formEmbeddingModel === m.value
                                  ? 'text-primary-600 dark:text-primary-400 font-medium bg-primary-50 dark:bg-slate-600'
                                  : 'text-slate-700 dark:text-slate-200'}`}
                            >
                              <span className="font-mono">{m.value}</span>
                              <span className="text-xs text-slate-400 dark:text-slate-500 ml-2 whitespace-nowrap">{m.label}</span>
                            </button>
                          ))}
                        </div>
                      )}
                    </div>
                  </div>

                  {formSupportsEmbedding && (
                    <div>
                      <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">
                        向量维度 <span className="text-slate-400 font-normal">（必须与 ES 向量索引维度一致，当前为 {DEFAULT_EMBEDDING_DIMENSIONS} 维）</span>
                      </label>
                      <input
                        type="number"
                        min={1}
                        value={formEmbeddingDimensions}
                        onChange={(e) => setFormEmbeddingDimensions(e.target.value)}
                        placeholder={String(DEFAULT_EMBEDDING_DIMENSIONS)}
                        className="dark-input w-full px-3 py-2.5 text-sm"
                      />
                    </div>
                  )}

                  {/* Temperature */}
                  <div>
                    <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">
                      Temperature <span className="text-slate-400 font-normal">(可选, 默认 0.2)</span>
                    </label>
                    <input
                      type="text"
                      value={formTemperature}
                      onChange={(e) => setFormTemperature(e.target.value)}
                      placeholder="例如: 0.2, 0.7, 1"
                      className="dark-input w-full px-3 py-2.5 text-sm"
                    />
                  </div>
                </div>

                {/* Modal actions */}
                <div className="flex gap-3 justify-end mt-6">
                  <button
                    onClick={closeModal}
                    disabled={saving}
                    className="btn-secondary px-4 py-2 text-sm disabled:opacity-50 disabled:cursor-not-allowed"
                  >
                    取消
                  </button>
                  <button
                    onClick={handleSaveModal}
                    disabled={saving}
                    className="btn-primary px-4 py-2 text-sm disabled:opacity-50 disabled:cursor-not-allowed"
                  >
                    <LoadingButtonContent loading={saving} loadingText="保存中...">
                      保存
                    </LoadingButtonContent>
                  </button>
                </div>
              </div>
            </div>
          </>
      )}

      <ConfirmDialog
        open={pendingDefaultProviderId !== null}
        title="设为默认聊天服务"
        message={`确定要将 "${pendingDefaultProviderId ?? ''}" 设为默认聊天服务吗？该操作不会改变知识库使用的向量模型。`}
        confirmText="确认设置"
        cancelText="取消"
        loading={settingDefault}
        onConfirm={handleConfirmSetDefault}
        onCancel={() => {
          if (!settingDefault) {
            setPendingDefaultProviderId(null);
          }
        }}
      />

      <ConfirmDialog
        open={pendingDefaultEmbeddingProviderId !== null}
        title="设为默认向量服务"
        message={`确定要将 "${pendingDefaultEmbeddingProviderId ?? ''}" 的向量模型 "${pendingEmbeddingProvider?.embeddingModel ?? ''}"（${pendingEmbeddingProvider?.embeddingDimensions ?? DEFAULT_EMBEDDING_DIMENSIONS}维）设为知识库默认向量服务吗？后续上传和重新向量化会使用这个向量模型，不会使用聊天模型。`}
        confirmText="确认设置"
        cancelText="取消"
        loading={settingEmbeddingDefault}
        onConfirm={handleConfirmSetEmbeddingDefault}
        onCancel={() => {
          if (!settingEmbeddingDefault) {
            setPendingDefaultEmbeddingProviderId(null);
          }
        }}
      />

      {/* Delete confirmation dialog */}
      {deleteConfirmId && (
          <>
            <div
              onClick={() => setDeleteConfirmId(null)}
              className="fixed inset-0 bg-black/50 z-50"
            />
            <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
              <div
                onClick={(e) => e.stopPropagation()}
                className="surface-card max-w-md w-full p-5"
              >
                <h3 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
                  删除模型服务
                </h3>
                <p className="text-slate-600 dark:text-slate-300 mb-6">
                  确定要删除模型服务 &ldquo;{deleteConfirmId}&rdquo; 吗？删除后无法恢复。
                  如果当前正在使用，请先切换到其他服务。
                </p>
                <div className="flex gap-3 justify-end">
                  <button
                    onClick={() => setDeleteConfirmId(null)}
                    disabled={deleting}
                    className="btn-secondary px-4 py-2 text-sm disabled:opacity-50 disabled:cursor-not-allowed"
                  >
                    取消
                  </button>
                  <button
                    onClick={handleDelete}
                    disabled={deleting}
                    className="rounded-lg bg-red-600 px-4 py-2 text-sm font-medium text-white hover:bg-red-700 disabled:opacity-50 disabled:cursor-not-allowed"
                  >
                    <LoadingButtonContent loading={deleting} loadingText="删除中...">
                      确定删除
                    </LoadingButtonContent>
                  </button>
                </div>
              </div>
            </div>
          </>
      )}

      {/* Toast notification */}
      {toast && (
          <div
            className={`fixed bottom-6 left-1/2 -translate-x-1/2 px-5 py-3 rounded-lg shadow-md text-sm font-medium
              flex items-center gap-2 z-[60] ${
                toast.type === 'success'
                  ? 'bg-emerald-600 text-white'
                  : 'bg-red-600 text-white'
              }`}
          >
            {toast.type === 'success'
              ? <CheckCircle className="w-4 h-4" />
              : <XCircle className="w-4 h-4" />
            }
            {toast.message}
          </div>
      )}
    </div>
  );
}
