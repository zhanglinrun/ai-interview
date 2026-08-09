import { FormEvent, useEffect, useMemo, useRef, useState } from 'react';
import {
  AlertTriangle,
  ArrowRight,
  Check,
  Code2,
  FileSearch,
  Github,
  Loader2,
  LockKeyhole,
  Plus,
  RefreshCw,
  ShieldCheck,
  Trash2,
} from 'lucide-react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { type ResumeListItem, historyApi } from '../api/history';
import {
  githubEvidenceApi,
  type GithubRepository,
  type GithubSyncStatus,
} from '../api/githubEvidence';
import { jobInterviewApi } from '../api/jobInterview';
import {
  jobTargetApi,
  type CreateJobTargetRequest,
  type JobCapabilityMapping,
  type JobTarget,
  type JobTrack,
} from '../api/jobTarget';
import { type KnowledgeBaseItem, knowledgeBaseApi } from '../api/knowledgebase';
import { getErrorMessage } from '../api/request';
import { EmptyState, LoadingState } from '../components/PageState';
import PageHeader from '../components/ui/PageHeader';

interface CapabilityDraft {
  enabled: boolean;
  weight: number;
}

const statusLabel: Record<JobTarget['status'], string> = {
  DRAFT: '待分析',
  ANALYZED: '待确认',
  FROZEN: '已确认',
  REDACTED: '已脱敏',
};

const githubSyncStatusLabel: Record<GithubSyncStatus, string> = {
  AWAITING_SELECTION: '待选择文件',
  SYNCING: '同步中',
  SYNCED: '已同步',
  PARTIAL: '部分同步',
  FAILED: '同步失败',
  SOURCE_UNAVAILABLE: '源仓库不可用',
};

const emptyJobForm: CreateJobTargetRequest = {
  title: '',
  company: '',
  jobTrack: 'JAVA_BACKEND',
  jdText: '',
  sourceUrl: '',
};

function repoDisplayName(repository: GithubRepository): string {
  return `${repository.owner}/${repository.repository}`;
}

const PREPARATION_RUN_STORAGE_PREFIX = 'ai-interview:job-preparation:';

function preparationRunStorageKey(jobDescriptionId: number): string {
  return `${PREPARATION_RUN_STORAGE_PREFIX}${jobDescriptionId}`;
}

function readPreparationRunId(jobDescriptionId: number): string | null {
  try {
    return window.sessionStorage.getItem(preparationRunStorageKey(jobDescriptionId));
  } catch {
    return null;
  }
}

function writePreparationRunId(jobDescriptionId: number, runId: string): void {
  try {
    window.sessionStorage.setItem(preparationRunStorageKey(jobDescriptionId), runId);
  } catch {
    // 隐私模式或禁用存储时仍允许当前页面继续等待任务。
  }
}

function clearPreparationRunId(jobDescriptionId: number): void {
  try {
    window.sessionStorage.removeItem(preparationRunStorageKey(jobDescriptionId));
  } catch {
    // 忽略浏览器存储不可用，任务本身已在后端持久化。
  }
}

export function formatCapabilityWeight(weight: number): string {
  const percentage = Math.round(weight * 1000) / 10;
  return Number.isInteger(percentage) ? `${percentage}%` : `${percentage.toFixed(1)}%`;
}

export default function JobPracticePage() {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const [targets, setTargets] = useState<JobTarget[]>([]);
  const [repositories, setRepositories] = useState<GithubRepository[]>([]);
  const [resumes, setResumes] = useState<ResumeListItem[]>([]);
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBaseItem[]>([]);
  const [selectedTargetId, setSelectedTargetId] = useState<number | null>(null);
  const [capabilityDrafts, setCapabilityDrafts] = useState<Record<number, CapabilityDraft>>({});
  const [showCreate, setShowCreate] = useState(false);
  const [showGithub, setShowGithub] = useState(false);
  const [jobForm, setJobForm] = useState<CreateJobTargetRequest>(emptyJobForm);
  const [githubForm, setGithubForm] = useState({
    repositoryUrl: '',
    coreModules: '',
    responsibilities: '',
    keyDecisions: '',
    problemsSolved: '',
  });
  const [resumeId, setResumeId] = useState<number | ''>('');
  const [githubRepositoryId, setGithubRepositoryId] = useState<number | ''>('');
  const [knowledgeBaseIds, setKnowledgeBaseIds] = useState<number[]>([]);
  const [codingLanguage, setCodingLanguage] = useState<'JAVA21' | 'PYTHON3'>('JAVA21');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const preparationResumeAttempted = useRef<number | null>(null);
  const pageActive = useRef(true);

  const selectedTarget = useMemo(
    () => targets.find((target) => target.id === selectedTargetId) ?? null,
    [selectedTargetId, targets],
  );

  const refresh = async (preferredTargetId?: number) => {
    const results = await Promise.allSettled([
      jobTargetApi.list(),
      githubEvidenceApi.list(),
      historyApi.getResumes(),
      knowledgeBaseApi.getAllKnowledgeBases('time', 'VECTOR_STORED'),
    ]);
    const [targetResult, repoResult, resumeResult, knowledgeResult] = results;
    if (targetResult.status === 'rejected') {
      throw targetResult.reason;
    }
    const loadedTargets = targetResult.value;
    if (repoResult.status === 'fulfilled') setRepositories(repoResult.value);
    if (resumeResult.status === 'fulfilled') {
      setResumes(resumeResult.value);
      const queryResume = Number(searchParams.get('resume'));
      if (Number.isInteger(queryResume) && resumeResult.value.some((item) => item.id === queryResume)) {
        setResumeId(queryResume);
      }
    }
    if (knowledgeResult.status === 'fulfilled') setKnowledgeBases(knowledgeResult.value);

    const queryTarget = Number(searchParams.get('target'));
    const nextId = preferredTargetId
      ?? (Number.isInteger(queryTarget) && loadedTargets.some((item) => item.id === queryTarget)
        ? queryTarget
        : loadedTargets[0]?.id);
    if (nextId != null) {
      const detail = await jobTargetApi.get(nextId);
      setTargets(loadedTargets.map((item) => item.id === nextId ? detail : item));
    } else {
      setTargets(loadedTargets);
    }
    setSelectedTargetId(nextId ?? null);
  };

  useEffect(() => {
    let active = true;
    refresh()
      .catch((reason) => {
        if (active) setError(getErrorMessage(reason, '岗位数据加载失败'));
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
    // 首次加载时读取 URL 中的 target，后续选择由本页状态驱动。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    pageActive.current = true;
    return () => {
      pageActive.current = false;
    };
  }, []);

  useEffect(() => {
    if (!selectedTarget) {
      setCapabilityDrafts({});
      return;
    }
    const nextDrafts = Object.fromEntries(selectedTarget.capabilities.map((mapping) => [
      mapping.id,
      {
        enabled: mapping.enabled,
        weight: Number(mapping.confirmedWeight ?? mapping.suggestedWeight ?? 0.5),
      },
    ]));
    setCapabilityDrafts(nextDrafts);
  }, [selectedTarget]);

  useEffect(() => {
    if (loading || !selectedTarget || preparationResumeAttempted.current === selectedTarget.id) {
      return;
    }
    const runId = readPreparationRunId(selectedTarget.id);
    preparationResumeAttempted.current = selectedTarget.id;
    if (!runId) return;

    let active = true;
    setBusy('prepare');
    setError('');
    setNotice('检测到未完成的面试准备，正在继续处理...');
    void (async () => {
      try {
        const current = await jobInterviewApi.getPreparation(runId);
        if (current.jobDescriptionId !== selectedTarget.id) {
          clearPreparationRunId(selectedTarget.id);
          return;
        }
        const ready = current.status === 'READY'
          ? current
          : await jobInterviewApi.waitForPreparation(runId);
        if (!ready.sessionId) {
          throw new Error('准备任务已结束，但没有生成面试会话');
        }
        clearPreparationRunId(selectedTarget.id);
        if (active) navigate(`/job-practice/session/${encodeURIComponent(ready.sessionId)}`);
      } catch (reason) {
        clearPreparationRunId(selectedTarget.id);
        if (active) {
          setError(getErrorMessage(reason, '恢复面试准备失败'));
          setNotice('');
        }
      } finally {
        if (active) setBusy('');
      }
    })();
    return () => {
      active = false;
    };
  }, [loading, navigate, selectedTarget?.id]);

  const chooseTarget = async (id: number) => {
    setSelectedTargetId(id);
    setSearchParams({ target: String(id) }, { replace: true });
    setError('');
    setNotice('');
    try {
      const detail = await jobTargetApi.get(id);
      setTargets((current) => current.map((item) => item.id === id ? detail : item));
    } catch (reason) {
      setError(getErrorMessage(reason, '岗位详情加载失败'));
    }
  };

  const createTarget = async (event: FormEvent) => {
    event.preventDefault();
    setBusy('create-target');
    setError('');
    try {
      const created = await jobTargetApi.create({
        ...jobForm,
        title: jobForm.title.trim(),
        company: jobForm.company?.trim() || undefined,
        jdText: jobForm.jdText.trim(),
        sourceUrl: jobForm.sourceUrl?.trim() || undefined,
      });
      await refresh(created.id);
      setJobForm(emptyJobForm);
      setShowCreate(false);
      setNotice('目标岗位已保存。下一步分析岗位要求，确认后即可开始面试。');
    } catch (reason) {
      setError(getErrorMessage(reason, '目标岗位创建失败'));
    } finally {
      setBusy('');
    }
  };

  const analyzeTarget = async () => {
    if (!selectedTarget) return;
    setBusy('analyze');
    setError('');
    try {
      const result = await jobTargetApi.analyze(selectedTarget.id);
      await refresh(selectedTarget.id);
      setNotice(result.fallbackUsed
        ? `已使用能力模板降级完成分析：${result.warning || '请人工确认所有能力。'}`
        : '岗位要求已整理，请检查重点是否准确。');
    } catch (reason) {
      setError(getErrorMessage(reason, 'JD 分析失败'));
    } finally {
      setBusy('');
    }
  };

  const saveCapabilities = async () => {
    if (!selectedTarget) return;
    const adjustments = selectedTarget.capabilities.map((mapping) => ({
      mappingId: mapping.id,
      enabled: capabilityDrafts[mapping.id]?.enabled ?? mapping.enabled,
      weight: capabilityDrafts[mapping.id]?.weight ?? Number(mapping.suggestedWeight),
    }));
    if (!adjustments.some((item) => item.enabled)) {
      setError('至少保留一项岗位能力');
      return;
    }
    setBusy('capabilities');
    setError('');
    try {
      await jobTargetApi.confirmCapabilities(selectedTarget.id, adjustments);
      await refresh(selectedTarget.id);
      setNotice('岗位重点已保存，可以确认这份岗位信息。');
    } catch (reason) {
      setError(getErrorMessage(reason, '能力确认失败'));
    } finally {
      setBusy('');
    }
  };

  const freezeTarget = async () => {
    if (!selectedTarget) return;
    setBusy('freeze');
    setError('');
    try {
      await jobTargetApi.freeze(selectedTarget.id);
      await refresh(selectedTarget.id);
      setNotice('岗位信息已确认，本场面试将使用当前版本。');
    } catch (reason) {
      setError(getErrorMessage(reason, '岗位确认失败'));
    } finally {
      setBusy('');
    }
  };

  const deleteTarget = async () => {
    if (!selectedTarget || !window.confirm(`确认删除目标岗位“${selectedTarget.title}”吗？`)) return;
    setBusy('delete-target');
    try {
      await jobTargetApi.delete(selectedTarget.id);
      await refresh();
      setNotice('目标岗位已删除。');
    } catch (reason) {
      setError(getErrorMessage(reason, '岗位删除失败'));
    } finally {
      setBusy('');
    }
  };

  const bindRepository = async (event: FormEvent) => {
    event.preventDefault();
    const coreModules = githubForm.coreModules
      .split(/[\n,，]/)
      .map((item) => item.trim())
      .filter(Boolean)
      .slice(0, 3);
    if (coreModules.length === 0) {
      setError('请填写 1 到 3 个本人核心模块');
      return;
    }
    setBusy('github-bind');
    setError('');
    try {
      const detail = await githubEvidenceApi.bind({
        repositoryUrl: githubForm.repositoryUrl.trim(),
        contribution: {
          coreModules,
          responsibilities: githubForm.responsibilities.trim(),
          keyDecisions: githubForm.keyDecisions.trim(),
          problemsSolved: githubForm.problemsSolved.trim(),
        },
      });
      await refresh(selectedTargetId ?? undefined);
      setGithubRepositoryId(detail.repository.id);
      setGithubForm({ repositoryUrl: '', coreModules: '', responsibilities: '', keyDecisions: '', problemsSolved: '' });
      setShowGithub(false);
      setNotice('公共仓库已绑定。你填写的职责会用于生成追问，不会直接当作事实。');
    } catch (reason) {
      setError(getErrorMessage(reason, 'GitHub 仓库绑定失败'));
    } finally {
      setBusy('');
    }
  };

  const syncRepository = async (repository: GithubRepository) => {
    if (!repository.fixedCommitSha) {
      setError('仓库版本还没有读取完成，暂时无法同步');
      return;
    }
    setBusy(`github-sync-${repository.id}`);
    setError('');
    try {
      const result = await githubEvidenceApi.sync(repository.id, repository.fixedCommitSha);
      await refresh(selectedTargetId ?? undefined);
      setNotice(`代码读取完成：${result.syncedFiles} 个文件、${result.evidenceChunks} 个可提问片段。`);
    } catch (reason) {
      setError(getErrorMessage(reason, 'GitHub 同步失败，请检查仓库地址或稍后重试'));
    } finally {
      setBusy('');
    }
  };

  const deleteRepository = async (repository: GithubRepository) => {
    if (!window.confirm(`确认删除 GitHub 仓库“${repoDisplayName(repository)}”吗？`)) return;
    setBusy(`github-delete-${repository.id}`);
    setError('');
    try {
      await githubEvidenceApi.delete(repository.id);
      if (githubRepositoryId === repository.id) setGithubRepositoryId('');
      await refresh(selectedTargetId ?? undefined);
      setNotice('GitHub 仓库已删除，历史报告不会继续展示仓库正文。');
    } catch (reason) {
      setError(getErrorMessage(reason, 'GitHub 仓库删除失败'));
    } finally {
      setBusy('');
    }
  };

  const toggleKnowledgeBase = (id: number) => {
    setKnowledgeBaseIds((current) => current.includes(id)
      ? current.filter((item) => item !== id)
      : [...current, id]);
  };

  const prepareInterview = async () => {
    if (!selectedTarget || selectedTarget.status !== 'FROZEN') return;
    setBusy('prepare');
    setError('');
    setNotice('正在根据岗位和所选资料准备面试，请稍候...');
    try {
      const preparation = await jobInterviewApi.createPreparation({
        jobDescriptionId: selectedTarget.id,
        resumeId: resumeId || null,
        githubRepositoryId: githubRepositoryId || null,
        knowledgeBaseIds,
        includePersonalMaterials: Boolean(resumeId || knowledgeBaseIds.length > 0),
        codingLanguage,
        regenerate: false,
      });
      writePreparationRunId(selectedTarget.id, preparation.runId);
      const ready = await jobInterviewApi.waitForPreparation(preparation.runId);
      if (!ready.sessionId) {
        throw new Error('准备任务已结束，但没有生成面试会话');
      }
      clearPreparationRunId(selectedTarget.id);
      if (pageActive.current) {
        navigate(`/job-practice/session/${encodeURIComponent(ready.sessionId)}`);
      }
    } catch (reason) {
      clearPreparationRunId(selectedTarget.id);
      if (pageActive.current) {
        setError(getErrorMessage(reason, '面试准备失败'));
        setNotice('');
      }
    } finally {
      if (pageActive.current) setBusy('');
    }
  };

  if (loading) return <LoadingState label="加载岗位信息..." />;

  return (
    <div className="mx-auto max-w-7xl">
      <PageHeader
        title="岗位实战"
        description="用一份真实 JD 准备面试。简历、复习资料和 GitHub 代码都可以不选。"
        action={(
          <button onClick={() => setShowCreate((value) => !value)} className="btn-primary inline-flex items-center gap-2 px-4 py-2.5 text-sm">
            <Plus className="h-4 w-4" />新增目标岗位
          </button>
        )}
      />

      {error && (
        <div className="mb-5 flex items-start gap-2 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800 dark:border-red-900/60 dark:bg-red-950/30 dark:text-red-300">
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />{error}
        </div>
      )}
      {notice && (
        <div className="mb-5 flex items-start gap-2 rounded-xl border border-primary-200 bg-primary-50 px-4 py-3 text-sm text-primary-800 dark:border-primary-900/60 dark:bg-primary-950/30 dark:text-primary-200">
          <Check className="mt-0.5 h-4 w-4 shrink-0" />{notice}
        </div>
      )}

      {showCreate && (
        <form onSubmit={createTarget} className="surface-card mb-5 p-5">
          <div className="mb-5 flex items-center justify-between">
            <div>
              <h2 className="text-lg font-semibold text-stone-900 dark:text-white">创建目标岗位</h2>
              <p className="mt-1 text-sm text-stone-500 dark:text-stone-400">粘贴岗位职责和任职要求，至少 50 字。</p>
            </div>
            <button type="button" onClick={() => setShowCreate(false)} className="text-sm text-stone-500">收起</button>
          </div>
          <div className="grid gap-4 md:grid-cols-2">
            <label className="text-sm text-stone-600 dark:text-stone-300">岗位名称
              <input required maxLength={120} value={jobForm.title} onChange={(event) => setJobForm({ ...jobForm, title: event.target.value })} className="dark-input mt-1.5 w-full px-3 py-2.5" placeholder="Java 后端开发工程师" />
            </label>
            <label className="text-sm text-stone-600 dark:text-stone-300">公司（可选）
              <input maxLength={120} value={jobForm.company} onChange={(event) => setJobForm({ ...jobForm, company: event.target.value })} className="dark-input mt-1.5 w-full px-3 py-2.5" placeholder="目标公司" />
            </label>
            <label className="text-sm text-stone-600 dark:text-stone-300">岗位方向
              <select value={jobForm.jobTrack} onChange={(event) => setJobForm({ ...jobForm, jobTrack: event.target.value as JobTrack })} className="dark-input mt-1.5 w-full px-3 py-2.5">
                <option value="JAVA_BACKEND">Java 后端</option>
                <option value="AI_RAG_AGENT">AI / RAG / Agent</option>
              </select>
            </label>
            <label className="text-sm text-stone-600 dark:text-stone-300">招聘来源（可选）
              <input type="url" maxLength={1000} value={jobForm.sourceUrl} onChange={(event) => setJobForm({ ...jobForm, sourceUrl: event.target.value })} className="dark-input mt-1.5 w-full px-3 py-2.5" placeholder="https://..." />
            </label>
          </div>
          <label className="mt-4 block text-sm text-stone-600 dark:text-stone-300">完整 JD
            <textarea required minLength={50} maxLength={30000} value={jobForm.jdText} onChange={(event) => setJobForm({ ...jobForm, jdText: event.target.value })} className="dark-input mt-1.5 min-h-52 w-full p-3 leading-6" placeholder="粘贴岗位职责、任职要求和加分项..." />
          </label>
          <button disabled={Boolean(busy)} className="btn-primary mt-4 inline-flex items-center gap-2 px-4 py-2.5 text-sm disabled:opacity-60">
            {busy === 'create-target' && <Loader2 className="h-4 w-4 animate-spin" />}保存目标岗位
          </button>
        </form>
      )}

      <div className="grid gap-5 lg:grid-cols-[260px_minmax(0,1fr)]">
        <aside className="surface-card h-fit overflow-hidden">
          <div className="border-b border-stone-200/80 px-4 py-3 dark:border-stone-800">
            <h2 className="text-sm font-semibold text-stone-900 dark:text-white">目标岗位</h2>
          </div>
          {targets.length === 0 ? (
            <EmptyState className="p-6 text-center" icon={FileSearch} iconClassName="mx-auto mb-3 h-9 w-9 text-stone-300" title="还没有目标岗位" titleClassName="text-sm font-medium text-stone-700 dark:text-stone-300" description="从招聘雷达找到 JD 后在这里创建。" descriptionClassName="mt-1 text-xs text-stone-400" />
          ) : (
            <div className="divide-y divide-stone-100 dark:divide-stone-800">
              {targets.map((target) => (
                <button key={target.id} onClick={() => chooseTarget(target.id)} className={`w-full px-4 py-3 text-left transition-colors ${selectedTargetId === target.id ? 'bg-primary-50 dark:bg-primary-950/30' : 'hover:bg-stone-50 dark:hover:bg-stone-900'}`}>
                  <div className="flex items-center justify-between gap-2">
                    <span className="truncate text-sm font-medium text-stone-900 dark:text-white">{target.title}</span>
                    <span className="shrink-0 text-[11px] text-primary-700 dark:text-primary-300">{statusLabel[target.status]}</span>
                  </div>
                  <p className="mt-1 truncate text-xs text-stone-400">{target.company || '未填写公司'}</p>
                </button>
              ))}
            </div>
          )}
        </aside>

        {!selectedTarget ? (
          <EmptyState icon={FileSearch} title="选择或创建目标岗位" description="添加岗位职责和任职要求后即可开始准备。" />
        ) : (
          <div className="space-y-5">
            <section className="surface-card p-5">
              <div className="flex flex-wrap items-start justify-between gap-4">
                <div>
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="rounded-full bg-primary-50 px-2.5 py-1 text-xs font-medium text-primary-700 dark:bg-primary-950/50 dark:text-primary-300">{statusLabel[selectedTarget.status]}</span>
                    <span className="text-xs text-stone-400">{selectedTarget.jobTrack === 'JAVA_BACKEND' ? 'Java 后端' : 'AI / RAG / Agent'}</span>
                  </div>
                  <h2 className="mt-3 text-xl font-semibold text-stone-900 dark:text-white">{selectedTarget.company ? `${selectedTarget.company} · ` : ''}{selectedTarget.title}</h2>
                  <p className="mt-2 line-clamp-3 max-w-3xl whitespace-pre-wrap text-sm leading-6 text-stone-500 dark:text-stone-400">{selectedTarget.jdText}</p>
                </div>
                <button onClick={deleteTarget} disabled={Boolean(busy)} className="inline-flex items-center gap-1.5 rounded-lg px-3 py-2 text-sm text-red-600 hover:bg-red-50 dark:hover:bg-red-950/30"><Trash2 className="h-4 w-4" />删除</button>
              </div>
              <div className="mt-5 flex flex-wrap gap-2">
                {selectedTarget.status !== 'FROZEN' && (
                  <button onClick={analyzeTarget} disabled={Boolean(busy)} className="btn-primary inline-flex items-center gap-2 px-4 py-2 text-sm disabled:opacity-60">
                    {busy === 'analyze' ? <Loader2 className="h-4 w-4 animate-spin" /> : <FileSearch className="h-4 w-4" />}
                    {selectedTarget.capabilities.length > 0 ? '重新分析 JD' : '分析 JD 能力'}
                  </button>
                )}
                {selectedTarget.status === 'FROZEN' && <span className="inline-flex items-center gap-2 text-sm text-emerald-700 dark:text-emerald-300"><LockKeyhole className="h-4 w-4" />岗位信息已确认</span>}
              </div>
            </section>

            {selectedTarget.capabilities.length > 0 && (
              <section className="surface-card p-5">
                <div className="mb-5 flex flex-wrap items-start justify-between gap-3">
                  <div>
                    <h2 className="text-lg font-semibold text-stone-900 dark:text-white">岗位重点</h2>
                    <p className="mt-1 text-sm text-stone-500 dark:text-stone-400">
                      {selectedTarget.status === 'FROZEN'
                        ? '以下重点已确认，将用于本次组卷和报告归因。'
                        : '取消不相关的内容，或调整面试时的关注程度。'}
                    </p>
                  </div>
                  {selectedTarget.status !== 'FROZEN' && (
                    <div className="flex gap-2">
                      <button onClick={saveCapabilities} disabled={Boolean(busy)} className="btn-secondary px-3 py-2 text-sm">保存确认</button>
                      <button onClick={freezeTarget} disabled={Boolean(busy)} className="btn-primary inline-flex items-center gap-2 px-3 py-2 text-sm"><LockKeyhole className="h-4 w-4" />确认岗位</button>
                    </div>
                  )}
                </div>
                <div className="grid gap-3 md:grid-cols-2">
                  {selectedTarget.capabilities.map((mapping: JobCapabilityMapping) => {
                    const draft = capabilityDrafts[mapping.id] ?? { enabled: mapping.enabled, weight: Number(mapping.suggestedWeight) };
                    return (
                      <div key={mapping.id} className={`rounded-xl border p-4 ${draft.enabled ? 'border-primary-200 bg-primary-50/40 dark:border-primary-900/50 dark:bg-primary-950/20' : 'border-stone-200 opacity-60 dark:border-stone-800'}`}>
                        <label className="flex items-center gap-2">
                          <input type="checkbox" checked={draft.enabled} disabled={selectedTarget.status === 'FROZEN'} onChange={(event) => setCapabilityDrafts({ ...capabilityDrafts, [mapping.id]: { ...draft, enabled: event.target.checked } })} className="accent-primary-600" />
                          <span className="font-medium text-stone-900 dark:text-white">{mapping.capabilityName}</span>
                          <span className="ml-auto text-xs text-stone-400">{formatCapabilityWeight(draft.weight)}</span>
                        </label>
                        {mapping.evidenceText && <p className="mt-3 line-clamp-3 text-xs leading-5 text-stone-500 dark:text-stone-400">“{mapping.evidenceText}”</p>}
                        <input type="range" min="0.01" max="1" step="0.01" value={draft.weight} disabled={!draft.enabled || selectedTarget.status === 'FROZEN'} onChange={(event) => setCapabilityDrafts({ ...capabilityDrafts, [mapping.id]: { ...draft, weight: Number(event.target.value) } })} className="mt-3 w-full accent-primary-600" />
                      </div>
                    );
                  })}
                </div>
              </section>
            )}

            <section className="surface-card p-5">
              <div className="flex flex-wrap items-start justify-between gap-3">
                <div>
                  <h2 className="flex items-center gap-2 text-lg font-semibold text-stone-900 dark:text-white"><Github className="h-5 w-5" />GitHub 项目</h2>
                  <p className="mt-1 text-sm text-stone-500 dark:text-stone-400">可读取公共仓库中的代码，用来追问你负责的模块和设计选择。</p>
                </div>
                <button onClick={() => setShowGithub((value) => !value)} className="btn-secondary inline-flex items-center gap-2 px-3 py-2 text-sm"><Plus className="h-4 w-4" />绑定仓库</button>
              </div>
              {showGithub && (
                <form onSubmit={bindRepository} className="mt-5 rounded-xl border border-stone-200 p-4 dark:border-stone-800">
                  <div className="grid gap-3 md:grid-cols-2">
                    <label className="text-sm text-stone-600 dark:text-stone-300">GitHub 公共仓库 URL<input required type="url" value={githubForm.repositoryUrl} onChange={(event) => setGithubForm({ ...githubForm, repositoryUrl: event.target.value })} className="dark-input mt-1 w-full px-3 py-2" placeholder="https://github.com/owner/repository" /></label>
                    <label className="text-sm text-stone-600 dark:text-stone-300">本人核心模块（1-3 个，逗号分隔）<input required value={githubForm.coreModules} onChange={(event) => setGithubForm({ ...githubForm, coreModules: event.target.value })} className="dark-input mt-1 w-full px-3 py-2" placeholder="RAG 检索, 面试编排" /></label>
                    <label className="text-sm text-stone-600 dark:text-stone-300">主要职责<textarea required value={githubForm.responsibilities} onChange={(event) => setGithubForm({ ...githubForm, responsibilities: event.target.value })} className="dark-input mt-1 min-h-24 w-full p-3" /></label>
                    <label className="text-sm text-stone-600 dark:text-stone-300">关键设计取舍<textarea required value={githubForm.keyDecisions} onChange={(event) => setGithubForm({ ...githubForm, keyDecisions: event.target.value })} className="dark-input mt-1 min-h-24 w-full p-3" /></label>
                    <label className="text-sm text-stone-600 dark:text-stone-300 md:col-span-2">解决的问题<textarea required value={githubForm.problemsSolved} onChange={(event) => setGithubForm({ ...githubForm, problemsSolved: event.target.value })} className="dark-input mt-1 min-h-24 w-full p-3" /></label>
                  </div>
                  <button disabled={Boolean(busy)} className="btn-primary mt-3 inline-flex items-center gap-2 px-4 py-2 text-sm disabled:opacity-60">{busy === 'github-bind' && <Loader2 className="h-4 w-4 animate-spin" />}绑定并检查文件清单</button>
                </form>
              )}
              {repositories.length > 0 && (
                <div className="mt-5 grid gap-3 md:grid-cols-2">
                  {repositories.map((repository) => (
                    <div key={repository.id} className="rounded-xl border border-stone-200 p-4 dark:border-stone-800">
                      <div className="flex items-center justify-between gap-3">
                        <span className="truncate font-medium text-stone-900 dark:text-white">{repoDisplayName(repository)}</span>
                        <span className="text-xs text-stone-400">
                          {githubSyncStatusLabel[repository.syncStatus]}
                        </span>
                      </div>
                      <p className="mt-2 text-xs text-stone-400">{repository.fixedCommitSha ? `同步版本 ${repository.fixedCommitSha.slice(0, 8)}` : '等待读取仓库版本'}</p>
                      <p className="mt-2 text-xs text-stone-500">已同步 {repository.syncedFileCount} 个文件 · {repository.coreModules.join(' / ')}</p>
                      {(repository.syncStatus === 'PARTIAL' || repository.syncStatus === 'FAILED') && repository.syncError && (
                        <p className="mt-2 flex items-start gap-1.5 text-xs text-amber-700 dark:text-amber-300">
                          <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden="true" />
                          <span>{repository.syncError}</span>
                        </p>
                      )}
                      <div className="mt-3 flex flex-wrap gap-2">
                        <button onClick={() => syncRepository(repository)} disabled={Boolean(busy) || !repository.fixedCommitSha} className="btn-secondary inline-flex items-center gap-2 px-3 py-1.5 text-xs disabled:opacity-50">{busy === `github-sync-${repository.id}` ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <RefreshCw className="h-3.5 w-3.5" />}重新读取</button>
                        <button onClick={() => deleteRepository(repository)} disabled={Boolean(busy)} className="inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs text-red-600 hover:bg-red-50 disabled:opacity-50 dark:hover:bg-red-950/30">{busy === `github-delete-${repository.id}` ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Trash2 className="h-3.5 w-3.5" />}删除</button>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </section>

            <section className="surface-card p-5">
              <div className="mb-5">
                <h2 className="flex items-center gap-2 text-lg font-semibold text-stone-900 dark:text-white"><ShieldCheck className="h-5 w-5 text-primary-600" />本次使用的资料</h2>
                <p className="mt-1 text-sm text-stone-500 dark:text-stone-400">岗位要求会自动使用，下面的个人资料都可以留空。</p>
              </div>
              <div className="grid gap-4 md:grid-cols-2">
                <label className="text-sm text-stone-600 dark:text-stone-300">个人简历（可选）
                  <select value={resumeId} onChange={(event) => setResumeId(event.target.value ? Number(event.target.value) : '')} className="dark-input mt-1.5 w-full px-3 py-2.5">
                    <option value="">不使用简历</option>
                    {resumes.map((resume) => <option key={resume.id} value={resume.id}>{resume.filename}</option>)}
                  </select>
                </label>
                <label className="text-sm text-stone-600 dark:text-stone-300">GitHub 仓库（可选）
                  <select value={githubRepositoryId} onChange={(event) => setGithubRepositoryId(event.target.value ? Number(event.target.value) : '')} className="dark-input mt-1.5 w-full px-3 py-2.5">
                    <option value="">不使用 GitHub 项目</option>
                    {repositories.map((repository) => <option key={repository.id} value={repository.id}>{repoDisplayName(repository)} · {githubSyncStatusLabel[repository.syncStatus]}</option>)}
                  </select>
                </label>
                <label className="text-sm text-stone-600 dark:text-stone-300">算法语言
                  <select value={codingLanguage} onChange={(event) => setCodingLanguage(event.target.value as 'JAVA21' | 'PYTHON3')} className="dark-input mt-1.5 w-full px-3 py-2.5"><option value="JAVA21">Java</option><option value="PYTHON3">Python 3</option></select>
                </label>
                <div className="text-sm text-stone-600 dark:text-stone-300">专项资料（可选）
                  <p className="mt-1.5 rounded-lg border border-stone-200 bg-stone-50 px-3 py-2.5 text-stone-500 dark:border-stone-700 dark:bg-stone-900 dark:text-stone-400">
                    {knowledgeBaseIds.length > 0 ? `已选 ${knowledgeBaseIds.length} 份资料` : '不使用个人知识资料'}
                  </p>
                </div>
              </div>
              {knowledgeBases.length > 0 && (
                <div className="mt-3 grid max-h-40 gap-2 overflow-y-auto rounded-xl border border-stone-200 p-3 dark:border-stone-800 sm:grid-cols-2 scrollbar-thin">
                  {knowledgeBases.map((knowledgeBase) => (
                    <label key={knowledgeBase.id} className="flex items-center gap-2 text-sm text-stone-600 dark:text-stone-300"><input type="checkbox" checked={knowledgeBaseIds.includes(knowledgeBase.id)} onChange={() => toggleKnowledgeBase(knowledgeBase.id)} className="accent-primary-600" /><span className="truncate">{knowledgeBase.name}</span></label>
                  ))}
                </div>
              )}
              <div className="mt-5 rounded-xl bg-stone-100 p-4 text-sm leading-6 text-stone-600 dark:bg-stone-800/70 dark:text-stone-300">
                面试会包含个人背景、项目追问、岗位技术题和一道 Hot 100 算法题。只有你选中的个人资料会参与提问。
              </div>
              <button onClick={prepareInterview} disabled={Boolean(busy) || selectedTarget.status !== 'FROZEN'} className="btn-primary mt-5 inline-flex items-center gap-2 px-5 py-3 text-sm disabled:cursor-not-allowed disabled:opacity-50">
                {busy === 'prepare' ? <Loader2 className="h-4 w-4 animate-spin" /> : <Code2 className="h-4 w-4" />}
                {selectedTarget.status === 'FROZEN' ? '准备并开始面试' : '先确认岗位'}
                {selectedTarget.status === 'FROZEN' && <ArrowRight className="h-4 w-4" />}
              </button>
            </section>
          </div>
        )}
      </div>
    </div>
  );
}
