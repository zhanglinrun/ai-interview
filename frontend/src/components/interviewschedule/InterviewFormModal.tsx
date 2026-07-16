import React, { useState } from 'react';
import {
  AlertCircle,
  CheckCircle,
  ChevronLeft,
  ChevronRight,
  Edit3,
  FileText,
  Trash2,
  X,
} from 'lucide-react';
import dayjs from 'dayjs';
import { interviewScheduleApi } from '../../api/interviewSchedule';
import { getErrorMessage } from '../../api/request';
import type { InterviewFormData, InterviewType, ParseResponse } from '../../types/interviewSchedule';
import LoadingButtonContent from '../LoadingButtonContent';

interface InterviewFormModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSubmit: (data: InterviewFormData) => Promise<void>;
  onDelete?: (id: number) => void;
  initialData?: InterviewFormData;
  mode: 'create' | 'edit';
}

type Step = 'text' | 'parse-result' | 'form';

const INTERVIEW_INVITE_EXAMPLE = `【阿里巴巴】后端开发工程师一面邀请
候选人：张三
面试时间：2026-04-15 19:30
面试形式：视频面试（腾讯会议）
会议链接：https://meeting.tencent.com/abc-defg-hij
面试轮次：第一轮技术面
面试官：李老师
备注：请提前10分钟入会，准备项目介绍与系统设计案例。`;

const FIELD_CLASS = 'dark-input w-full px-3 py-2.5 text-sm';
const LABEL_CLASS = 'mb-1.5 block text-sm font-medium text-stone-700 dark:text-stone-300';

const createEmptyFormData = (): InterviewFormData => ({
  companyName: '',
  position: '',
  interviewTime: '',
  interviewType: 'VIDEO',
  meetingLink: '',
  roundNumber: 1,
  interviewer: '',
  notes: '',
});

const normalizeFormData = (data?: InterviewFormData | null): InterviewFormData => ({
  ...createEmptyFormData(),
  ...data,
  companyName: data?.companyName ?? '',
  position: data?.position ?? '',
  interviewTime: data?.interviewTime ?? '',
  interviewType: toInterviewType(data?.interviewType ?? 'VIDEO'),
  meetingLink: data?.meetingLink ?? '',
  roundNumber: Number.isFinite(data?.roundNumber) ? Math.max(1, data?.roundNumber ?? 1) : 1,
  interviewer: data?.interviewer ?? '',
  notes: data?.notes ?? '',
});

const toInterviewType = (value: string): InterviewType => {
  if (value === 'ONSITE' || value === 'PHONE') {
    return value;
  }
  return 'VIDEO';
};

export const InterviewFormModal: React.FC<InterviewFormModalProps> = ({
  isOpen,
  onClose,
  onSubmit,
  onDelete,
  initialData,
  mode,
}) => {
  const [step, setStep] = useState<Step>(mode === 'edit' ? 'form' : 'text');
  const [rawText, setRawText] = useState('');
  const [parseResult, setParseResult] = useState<ParseResponse | null>(null);
  const [parsing, setParsing] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [formData, setFormData] = useState<InterviewFormData>(
    normalizeFormData(initialData),
  );

  React.useEffect(() => {
    if (isOpen) {
      setStep(mode === 'edit' ? 'form' : 'text');
      setRawText('');
      setParseResult(null);
      setSubmitError(null);
      setFormData(normalizeFormData(initialData));
    }
  }, [isOpen, mode, initialData]);

  if (!isOpen) return null;

  const handleParse = async () => {
    if (!rawText.trim()) return;

    setParsing(true);
    try {
      const result = await interviewScheduleApi.parse(rawText);
      setParseResult(result);
      if (result.success && result.data) {
        setFormData(normalizeFormData(result.data));
      }
      setStep('parse-result');
    } catch (error) {
      console.error('Parse failed:', error);
      setParseResult({
        success: false,
        data: null,
        confidence: 0,
        parseMethod: 'ai',
        log: '解析失败，请手动输入',
      });
      setStep('parse-result');
    } finally {
      setParsing(false);
    }
  };

  const handleFormChange = <K extends keyof InterviewFormData>(
    field: K,
    value: InterviewFormData[K],
  ) => {
    setFormData((previous) => ({ ...previous, [field]: value }));
  };

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    setSubmitting(true);
    setSubmitError(null);
    try {
      await onSubmit(formData);
      onClose();
    } catch (error: unknown) {
      console.error('Submit failed:', error);
      setSubmitError(getErrorMessage(error, '保存失败，请重试'));
    } finally {
      setSubmitting(false);
    }
  };

  const handleReset = () => {
    setStep('text');
    setRawText('');
    setParseResult(null);
    setFormData(createEmptyFormData());
  };

  const renderTextInput = () => (
    <div className="space-y-5">
      <p className="text-sm leading-6 text-stone-500 dark:text-stone-400">
        粘贴收到的面试通知，我们会帮你提取公司、岗位和时间；也可以直接填写。
      </p>

      <div className="grid gap-3 sm:grid-cols-2">
        <button
          type="button"
          className="rounded-lg border border-primary-400 bg-primary-50 p-3 text-left dark:border-primary-700 dark:bg-primary-950/30"
          aria-pressed="true"
        >
          <span className="flex items-center gap-2.5">
            <span className="rounded-md bg-primary-600 p-2 text-white">
              <FileText className="h-4 w-4" />
            </span>
            <span>
              <span className="block text-sm font-semibold text-stone-900 dark:text-stone-50">
                从邀约中提取
              </span>
              <span className="block text-xs text-stone-500 dark:text-stone-400">
                粘贴通知后自动填写
              </span>
            </span>
          </span>
        </button>
        <button
          type="button"
          onClick={() => setStep('form')}
          className="rounded-lg border border-stone-200 bg-white p-3 text-left hover:border-stone-300 dark:border-stone-700 dark:bg-stone-900 dark:hover:border-stone-600"
        >
          <span className="flex items-center gap-2.5">
            <span className="rounded-md bg-stone-100 p-2 text-stone-600 dark:bg-stone-800 dark:text-stone-300">
              <Edit3 className="h-4 w-4" />
            </span>
            <span>
              <span className="block text-sm font-semibold text-stone-900 dark:text-stone-50">
                直接填写
              </span>
              <span className="block text-xs text-stone-500 dark:text-stone-400">
                手动记录面试安排
              </span>
            </span>
          </span>
        </button>
      </div>

      <div>
        <div className="mb-2 flex items-center justify-between gap-3">
          <label htmlFor="interview-invite" className="text-sm font-medium text-stone-700 dark:text-stone-300">
            面试通知
          </label>
          <button
            type="button"
            onClick={() => setRawText(INTERVIEW_INVITE_EXAMPLE)}
            className="text-xs font-medium text-primary-700 hover:text-primary-800 dark:text-primary-400"
          >
            使用示例
          </button>
        </div>
        <textarea
          id="interview-invite"
          value={rawText}
          onChange={(event) => setRawText(event.target.value)}
          placeholder="粘贴短信、邮件或聊天中的面试通知……"
          className={`${FIELD_CLASS} min-h-40 resize-y`}
        />
      </div>

      <div className="flex justify-end gap-2">
        <button type="button" onClick={onClose} className="btn-secondary px-4 py-2 text-sm">
          取消
        </button>
        <button
          type="button"
          onClick={handleParse}
          disabled={!rawText.trim() || parsing}
          className="btn-primary px-4 py-2 text-sm disabled:cursor-not-allowed disabled:opacity-50"
        >
          <LoadingButtonContent loading={parsing} loadingText="识别中...">
            识别信息
          </LoadingButtonContent>
        </button>
      </div>
    </div>
  );

  const renderParseResult = () => (
    <div className="space-y-5">
      {parseResult && (
        <div
          className={`rounded-lg border p-4 ${
            parseResult.success
              ? 'border-emerald-200 bg-emerald-50/70 dark:border-emerald-900 dark:bg-emerald-950/20'
              : 'border-red-200 bg-red-50/70 dark:border-red-900 dark:bg-red-950/20'
          }`}
        >
          <div className="mb-3 flex items-center gap-2">
            {parseResult.success ? (
              <CheckCircle className="h-5 w-5 text-emerald-600 dark:text-emerald-400" />
            ) : (
              <AlertCircle className="h-5 w-5 text-red-600 dark:text-red-400" />
            )}
            <span className="font-semibold text-stone-900 dark:text-stone-50">
              {parseResult.success ? '已识别，请核对' : '没有识别出完整信息'}
            </span>
          </div>

          {parseResult.success && parseResult.data ? (
            <dl className="grid gap-2 rounded-lg border border-stone-200 bg-white p-3 text-sm dark:border-stone-800 dark:bg-stone-900 sm:grid-cols-2">
              <div>
                <dt className="text-stone-500 dark:text-stone-400">公司</dt>
                <dd className="font-medium text-stone-900 dark:text-stone-100">{parseResult.data.companyName}</dd>
              </div>
              <div>
                <dt className="text-stone-500 dark:text-stone-400">岗位</dt>
                <dd className="font-medium text-stone-900 dark:text-stone-100">{parseResult.data.position}</dd>
              </div>
              <div>
                <dt className="text-stone-500 dark:text-stone-400">时间</dt>
                <dd className="font-medium text-stone-900 dark:text-stone-100">
                  {dayjs(parseResult.data.interviewTime).format('YYYY-MM-DD HH:mm')}
                </dd>
              </div>
              {parseResult.data.meetingLink && (
                <div className="min-w-0">
                  <dt className="text-stone-500 dark:text-stone-400">会议链接</dt>
                  <dd className="truncate font-medium text-stone-900 dark:text-stone-100">
                    {parseResult.data.meetingLink}
                  </dd>
                </div>
              )}
            </dl>
          ) : (
            <p className="text-sm text-stone-600 dark:text-stone-300">
              你可以返回修改通知文本，或改为手动填写。
            </p>
          )}
        </div>
      )}

      <div className="flex flex-wrap justify-between gap-2">
        <button
          type="button"
          onClick={() => setStep('text')}
          className="btn-secondary flex items-center gap-1.5 px-4 py-2 text-sm"
        >
          <ChevronLeft className="h-4 w-4" />
          返回修改
        </button>
        <button
          type="button"
          onClick={() => setStep('form')}
          className="btn-primary flex items-center gap-1.5 px-4 py-2 text-sm"
        >
          {parseResult?.success ? '核对并保存' : '手动填写'}
          <ChevronRight className="h-4 w-4" />
        </button>
      </div>
    </div>
  );

  const renderForm = () => (
    <form onSubmit={handleSubmit} className="space-y-4">
      <div className="grid gap-4 sm:grid-cols-2">
        <div>
          <label className={LABEL_CLASS}>
            公司名称 <span className="text-red-500">*</span>
          </label>
          <input
            type="text"
            value={formData.companyName}
            onChange={(event) => handleFormChange('companyName', event.target.value)}
            required
            className={FIELD_CLASS}
          />
        </div>
        <div>
          <label className={LABEL_CLASS}>
            岗位 <span className="text-red-500">*</span>
          </label>
          <input
            type="text"
            value={formData.position}
            onChange={(event) => handleFormChange('position', event.target.value)}
            required
            className={FIELD_CLASS}
          />
        </div>
      </div>

      <div className="grid gap-4 sm:grid-cols-2">
        <div>
          <label className={LABEL_CLASS}>
            面试时间 <span className="text-red-500">*</span>
          </label>
          <input
            type="datetime-local"
            value={formData.interviewTime ? dayjs(formData.interviewTime).format('YYYY-MM-DDTHH:mm') : ''}
            onChange={(event) => handleFormChange('interviewTime', event.target.value)}
            required
            className={FIELD_CLASS}
          />
        </div>
        <div>
          <label className={LABEL_CLASS}>面试形式</label>
          <select
            value={formData.interviewType}
            onChange={(event) => handleFormChange('interviewType', toInterviewType(event.target.value))}
            className={FIELD_CLASS}
          >
            <option value="VIDEO">视频面试</option>
            <option value="ONSITE">现场面试</option>
            <option value="PHONE">电话面试</option>
          </select>
        </div>
      </div>

      <div>
        <label className={LABEL_CLASS}>会议链接</label>
        <input
          type="url"
          value={formData.meetingLink}
          onChange={(event) => handleFormChange('meetingLink', event.target.value)}
          placeholder="https://"
          className={FIELD_CLASS}
        />
      </div>

      <div className="grid gap-4 sm:grid-cols-2">
        <div>
          <label className={LABEL_CLASS}>面试轮次</label>
          <input
            type="number"
            min="1"
            value={formData.roundNumber}
            onChange={(event) => handleFormChange(
              'roundNumber',
              event.target.value ? Math.max(1, parseInt(event.target.value, 10)) : 1,
            )}
            className={FIELD_CLASS}
          />
        </div>
        <div>
          <label className={LABEL_CLASS}>面试官</label>
          <input
            type="text"
            value={formData.interviewer}
            onChange={(event) => handleFormChange('interviewer', event.target.value)}
            className={FIELD_CLASS}
          />
        </div>
      </div>

      <div>
        <label className={LABEL_CLASS}>备注</label>
        <textarea
          value={formData.notes}
          onChange={(event) => handleFormChange('notes', event.target.value)}
          rows={3}
          className={`${FIELD_CLASS} resize-y`}
        />
      </div>

      {submitError && (
        <div className="flex items-start gap-2 rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-700 dark:border-red-900 dark:bg-red-950/30 dark:text-red-300">
          <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" />
          {submitError}
        </div>
      )}

      <div className="flex flex-wrap items-center justify-between gap-2 border-t border-stone-200 pt-4 dark:border-stone-800">
        {mode === 'create' && step !== 'text' ? (
          <button type="button" onClick={handleReset} className="btn-secondary px-4 py-2 text-sm">
            重新添加
          </button>
        ) : mode === 'edit' && onDelete && initialData?.id !== undefined ? (
          <button
            type="button"
            onClick={() => {
              if (initialData.id !== undefined) {
                onDelete(initialData.id);
              }
            }}
            className="flex items-center gap-1.5 rounded-lg border border-red-200 px-4 py-2 text-sm font-medium text-red-600 hover:bg-red-50 dark:border-red-900 dark:text-red-400 dark:hover:bg-red-950/30"
          >
            <Trash2 className="h-4 w-4" />
            删除
          </button>
        ) : <span />}
        <div className="ml-auto flex gap-2">
          <button type="button" onClick={onClose} className="btn-secondary px-4 py-2 text-sm">
            取消
          </button>
          <button
            type="submit"
            disabled={submitting}
            className="btn-primary px-4 py-2 text-sm disabled:cursor-not-allowed disabled:opacity-50"
          >
            <LoadingButtonContent loading={submitting} loadingText="保存中...">
              保存日程
            </LoadingButtonContent>
          </button>
        </div>
      </div>
    </form>
  );

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4">
      <section
        role="dialog"
        aria-modal="true"
        aria-labelledby="schedule-modal-title"
        className="surface-card flex max-h-[90vh] w-full max-w-2xl flex-col overflow-hidden"
      >
        <header className="flex shrink-0 items-center justify-between border-b border-stone-200 px-4 py-3 dark:border-stone-800 sm:px-5">
          <h2 id="schedule-modal-title" className="text-lg font-semibold text-stone-900 dark:text-stone-50">
            {mode === 'edit' ? '编辑面试日程' : '新建面试日程'}
          </h2>
          <button
            type="button"
            onClick={onClose}
            className="rounded-lg p-2 text-stone-400 hover:bg-stone-100 hover:text-stone-700 dark:hover:bg-stone-800 dark:hover:text-stone-200"
            aria-label="关闭"
          >
            <X className="h-5 w-5" />
          </button>
        </header>

        <div className="overflow-y-auto p-4 sm:p-5">
          {step === 'text' && renderTextInput()}
          {step === 'parse-result' && renderParseResult()}
          {step === 'form' && renderForm()}
        </div>
      </section>
    </div>
  );
};
