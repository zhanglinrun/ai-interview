import { useState } from 'react';
import { CheckCircle, Eye, EyeOff, KeyRound, Plug, Trash2, XCircle } from 'lucide-react';
import { userLlmProviderApi } from '../api/userLlmProvider';
import { getErrorMessage } from '../api/request';
import type { MyProviderDTO, ProviderTestResult } from '../types/userLlmProvider';
import ConfirmDialog from './ConfirmDialog';
import LoadingButtonContent from './LoadingButtonContent';

type ToastFn = (message: string, type: 'success' | 'error') => void;

interface MyModelFormProps {
  /** 当前已保存的配置（用于回显 baseUrl/chatModel/temperature 与「已配置」状态）。 */
  initial: MyProviderDTO | null;
  /** 保存成功后回调（父级通常据此刷新回显或关闭向导）。 */
  onSaved?: () => void;
  /** 删除成功后回调。 */
  onDeleted?: () => void;
  /** 保存/删除结果提示（不传则内部静默，测试结果始终内联展示）。 */
  onToast?: ToastFn;
  /** 是否显示「删除」按钮（设置页显示，向导不显示）。 */
  showDelete?: boolean;
  /** 主按钮文案，默认「保存」；向导里用「完成」。 */
  saveLabel?: string;
  /** 主按钮加载文案。 */
  savingLabel?: string;
}

const BASE_URL_PRESETS: { label: string; baseUrl: string; model: string }[] = [
  { label: '通义千问', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', model: 'qwen-plus' },
  { label: 'DeepSeek', baseUrl: 'https://api.deepseek.com', model: 'deepseek-chat' },
  { label: 'Kimi', baseUrl: 'https://api.moonshot.cn/v1', model: 'kimi-latest' },
  { label: '智谱 GLM', baseUrl: 'https://open.bigmodel.cn/api/paas/v4', model: 'glm-4-flash' },
];

const INPUT_CLASS = 'dark-input w-full px-3 py-2.5 text-sm';

const LABEL_CLASS = 'block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5';

function parseTemperature(raw: string): number | undefined {
  const trimmed = raw.trim();
  if (!trimmed) {
    return undefined;
  }
  const value = Number(trimmed);
  return Number.isFinite(value) ? value : undefined;
}

function isTemperatureValid(raw: string): boolean {
  if (!raw.trim()) return true;
  const value = parseTemperature(raw);
  return value !== undefined && value >= 0 && value <= 2;
}

export function formatTemperatureInput(value: number | null | undefined): string {
  if (value == null || !Number.isFinite(value)) {
    return '';
  }
  return String(Math.round((value + Number.EPSILON) * 1_000) / 1_000);
}

export default function MyModelForm({
  initial,
  onSaved,
  onDeleted,
  onToast,
  showDelete = false,
  saveLabel = '保存',
  savingLabel = '保存中...',
}: MyModelFormProps) {
  const [baseUrl, setBaseUrl] = useState(initial?.baseUrl ?? '');
  const [apiKey, setApiKey] = useState('');
  const [chatModel, setChatModel] = useState(initial?.chatModel ?? '');
  const [temperature, setTemperature] = useState(formatTemperatureInput(initial?.temperature));
  const [showApiKey, setShowApiKey] = useState(false);
  const [configured, setConfigured] = useState(Boolean(initial?.configured));
  const [maskedApiKey, setMaskedApiKey] = useState(initial?.maskedApiKey ?? '');

  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [testResult, setTestResult] = useState<ProviderTestResult | null>(null);
  const [error, setError] = useState('');

  const busy = saving || testing || deleting;

  const applyPreset = (preset: (typeof BASE_URL_PRESETS)[number]) => {
    setBaseUrl(preset.baseUrl);
    if (!chatModel.trim()) {
      setChatModel(preset.model);
    }
    setError('');
  };

  const validateForSave = (): { baseUrl: string; apiKey: string; chatModel: string } | null => {
    const trimmedBaseUrl = baseUrl.trim();
    const trimmedApiKey = apiKey.trim();
    const trimmedModel = chatModel.trim();
    if (!trimmedBaseUrl || !trimmedModel) {
      setError('请填写 Base URL 与 聊天模型');
      return null;
    }
    if (!isTemperatureValid(temperature)) {
      setError('随机性需填写 0 到 2 之间的数字');
      return null;
    }
    // 已配置后仅改 baseUrl/模型名时 Key 可留空（后端保留原 Key）；首次配置必须填写。
    if (!trimmedApiKey && !configured) {
      setError('请输入 API Key');
      return null;
    }
    return { baseUrl: trimmedBaseUrl, apiKey: trimmedApiKey, chatModel: trimmedModel };
  };

  const persist = async (payload: { baseUrl: string; apiKey: string; chatModel: string }) => {
    await userLlmProviderApi.saveMine({
      baseUrl: payload.baseUrl,
      // 留空则不提交 Key，后端在已有配置上保留原密文；有值才更新
      apiKey: payload.apiKey || undefined,
      chatModel: payload.chatModel,
      temperature: parseTemperature(temperature),
    });
    setConfigured(true);
    if (payload.apiKey) {
      setMaskedApiKey(`****${payload.apiKey.slice(-4)}`);
    }
  };

  const handleSave = async () => {
    const payload = validateForSave();
    if (!payload) {
      return;
    }
    setSaving(true);
    setError('');
    try {
      await persist(payload);
      setApiKey('');
      onToast?.('已保存「我的模型」配置', 'success');
      onSaved?.();
    } catch (err) {
      onToast?.(getErrorMessage(err, '保存失败'), 'error');
      setError(getErrorMessage(err, '保存失败'));
    } finally {
      setSaving(false);
    }
  };

  const handleTest = async () => {
    const trimmedBaseUrl = baseUrl.trim();
    const trimmedModel = chatModel.trim();
    const trimmedApiKey = apiKey.trim();
    if (!trimmedBaseUrl || !trimmedModel) {
      setError('请先填写 Base URL 与 聊天模型');
      return;
    }
    if (!isTemperatureValid(temperature)) {
      setError('随机性需填写 0 到 2 之间的数字');
      return;
    }
    // /mine/test 测的是「已保存」的配置。未配置且未填 Key 无从测起，先提示。
    if (!trimmedApiKey && !configured) {
      setError('请先输入 API Key');
      return;
    }
    setTesting(true);
    setError('');
    setTestResult(null);
    try {
      // 先保存当前表单再测，保证测到的是当前所填 baseUrl/模型；Key 留空时后端保留原 Key。
      await persist({ baseUrl: trimmedBaseUrl, apiKey: trimmedApiKey, chatModel: trimmedModel });
      if (trimmedApiKey) {
        setApiKey('');
      }
      const result = await userLlmProviderApi.testMine();
      setTestResult(result);
    } catch (err) {
      setTestResult({ success: false, message: getErrorMessage(err, '测试失败'), model: trimmedModel });
    } finally {
      setTesting(false);
    }
  };

  const handleDelete = async () => {
    setDeleting(true);
    try {
      await userLlmProviderApi.deleteMine();
      setConfigured(false);
      setMaskedApiKey('');
      setApiKey('');
      setBaseUrl('');
      setChatModel('');
      setTemperature('');
      setTestResult(null);
      setConfirmDelete(false);
      onToast?.('已删除「我的模型」配置', 'success');
      onDeleted?.();
    } catch (err) {
      onToast?.(getErrorMessage(err, '删除失败'), 'error');
    } finally {
      setDeleting(false);
    }
  };

  return (
    <div className="space-y-4">
      {/* 已配置状态提示 */}
      {configured && (
        <div className="flex items-center gap-2 rounded-lg bg-primary-50 dark:bg-primary-900/20 px-3 py-2 text-xs font-medium text-primary-700 dark:text-primary-300">
          <CheckCircle className="h-3.5 w-3.5 flex-shrink-0" />
          <span>模型 Key 已保存{maskedApiKey ? `：${maskedApiKey}` : ''}。只修改其他字段时，Key 可以留空。</span>
        </div>
      )}

      {/* Base URL 预设 */}
      <div className="flex flex-wrap gap-2">
        {BASE_URL_PRESETS.map((preset) => (
          <button
            key={preset.label}
            type="button"
            onClick={() => applyPreset(preset)}
            className="inline-flex items-center rounded-md border border-slate-200 dark:border-slate-600
              px-3 py-1 text-xs font-medium text-slate-600 dark:text-slate-300
              hover:border-primary-400 hover:text-primary-600 dark:hover:text-primary-300 transition-colors"
          >
            {preset.label}
          </button>
        ))}
      </div>

      {/* Base URL */}
      <div>
        <label className={LABEL_CLASS}>
          Base URL <span className="text-red-500">*</span>
        </label>
        <input
          type="text"
          value={baseUrl}
          onChange={(e) => setBaseUrl(e.target.value)}
          placeholder="例如: https://dashscope.aliyuncs.com/compatible-mode/v1"
          className={INPUT_CLASS}
        />
        <p className="mt-1 text-xs text-slate-400">兼容 OpenAI 的 /chat/completions 接口地址即可。</p>
      </div>

      {/* API Key */}
      <div>
        <label className={LABEL_CLASS}>
          API Key {configured
            ? <span className="text-slate-400 font-normal">(留空则不修改)</span>
            : <span className="text-red-500">*</span>}
        </label>
        <div className="relative">
          <input
            type={showApiKey ? 'text' : 'password'}
            value={apiKey}
            onChange={(e) => setApiKey(e.target.value)}
            placeholder={configured ? '留空则保持已配置的 Key 不变' : '输入你自己的模型 API Key'}
            autoComplete="off"
            className={`${INPUT_CLASS} pr-10`}
          />
          <button
            type="button"
            onClick={() => setShowApiKey((v) => !v)}
            className="absolute right-3 top-1/2 -translate-y-1/2 text-slate-400
              hover:text-slate-600 dark:hover:text-slate-300 transition-colors"
            title={showApiKey ? '隐藏' : '显示'}
          >
            {showApiKey ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
          </button>
        </div>
      </div>

      {/* Chat Model */}
      <div>
        <label className={LABEL_CLASS}>
          聊天模型 <span className="text-red-500">*</span>
        </label>
        <input
          type="text"
          value={chatModel}
          onChange={(e) => setChatModel(e.target.value)}
          placeholder="例如: qwen-plus, deepseek-chat, glm-4-flash"
          className={INPUT_CLASS}
        />
      </div>

      {/* Temperature */}
      <div>
        <label className={LABEL_CLASS}>
          回答随机性 <span className="text-slate-400 font-normal">（0-2，可选，默认 0.2）</span>
        </label>
        <input
          type="number"
          min={0}
          max={2}
          step={0.1}
          value={temperature}
          onChange={(e) => setTemperature(e.target.value)}
          placeholder="例如 0.2"
          className={INPUT_CLASS}
        />
      </div>

      {/* 校验错误 */}
      {error && <p className="text-sm text-red-500">{error}</p>}

      {/* 测试结果 */}
      {testResult && (
        <div
          className={`px-3 py-2 rounded-lg text-xs font-medium ${
            testResult.success
              ? 'bg-emerald-50 dark:bg-emerald-900/20 text-emerald-700 dark:text-emerald-300'
              : 'bg-red-50 dark:bg-red-900/20 text-red-700 dark:text-red-300'
          }`}
        >
          <div className="flex items-center gap-1.5">
            {testResult.success
              ? <CheckCircle className="h-3.5 w-3.5 flex-shrink-0" />
              : <XCircle className="h-3.5 w-3.5 flex-shrink-0" />}
            <span>{testResult.message}</span>
          </div>
        </div>
      )}

      {/* 操作按钮 */}
      <div className="flex flex-wrap items-center gap-3 pt-1">
        <button
          type="button"
          onClick={handleTest}
          disabled={busy}
          className="btn-secondary inline-flex items-center gap-2 px-4 py-2 text-sm disabled:opacity-50 disabled:cursor-not-allowed"
        >
          <LoadingButtonContent loading={testing} loadingText="保存并测试中...">
            <span className="inline-flex items-center gap-2">
              <Plug className="h-4 w-4" />
              保存并测试
            </span>
          </LoadingButtonContent>
        </button>

        <button
          type="button"
          onClick={handleSave}
          disabled={busy}
          className="btn-primary inline-flex items-center gap-2 px-4 py-2 text-sm disabled:opacity-50 disabled:cursor-not-allowed"
        >
          <LoadingButtonContent loading={saving} loadingText={savingLabel}>
            <span className="inline-flex items-center gap-2">
              <KeyRound className="h-4 w-4" />
              {saveLabel}
            </span>
          </LoadingButtonContent>
        </button>

        {showDelete && configured && (
          <button
            type="button"
            onClick={() => setConfirmDelete(true)}
            disabled={busy}
            className="ml-auto inline-flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm font-medium
              text-slate-400 hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-900/20
              transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
            title="删除我的模型配置"
          >
            <Trash2 className="h-4 w-4" />
            删除
          </button>
        )}
      </div>

      <ConfirmDialog
        open={confirmDelete}
        title="删除「我的模型」配置"
        message="删除后，知识问答、面试出题和评估将暂时不可用，重新填写模型 Key 后即可恢复。确定删除吗？"
        confirmText="确定删除"
        cancelText="取消"
        confirmVariant="danger"
        loading={deleting}
        onConfirm={handleDelete}
        onCancel={() => {
          if (!deleting) {
            setConfirmDelete(false);
          }
        }}
      />
    </div>
  );
}
