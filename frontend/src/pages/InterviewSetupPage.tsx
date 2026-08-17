import { FormEvent, useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { ArrowRight, FileText, Loader2 } from 'lucide-react';
import { historyApi, type ResumeListItem } from '../api/history';
import { knowledgeBaseApi, type KnowledgeBaseItem } from '../api/knowledgebase';
import { getErrorMessage } from '../api/request';
import { EmptyState, LoadingState } from '../components/PageState';
import PageHeader from '../components/ui/PageHeader';

export interface InterviewSetupResult {
  resumeId?: number;
  resumeText: string;
  skillId: string;
  questionCount: number;
  jdText: string;
  knowledgeBaseIds: number[];
}

const MIN_JD_CHARS = 50;
const UNCATEGORIZED = '未分类';
const DEFAULT_QUESTION_COUNT = 8;

const TOPICS = [
  { id: 'java-backend', label: 'Java 后端' },
  { id: 'ai-rag-agent', label: 'AI / RAG / Agent' },
] as const;

export function groupKnowledgeBasesByCategory(items: KnowledgeBaseItem[]): Array<{
  category: string;
  items: KnowledgeBaseItem[];
}> {
  const groups = new Map<string, KnowledgeBaseItem[]>();
  for (const item of items) {
    const category = item.category?.trim() || UNCATEGORIZED;
    const current = groups.get(category);
    if (current) {
      current.push(item);
    } else {
      groups.set(category, [item]);
    }
  }
  return [...groups.entries()]
    .sort(([left], [right]) => {
      if (left === UNCATEGORIZED) return 1;
      if (right === UNCATEGORIZED) return -1;
      return left.localeCompare(right, 'zh-CN');
    })
    .map(([category, grouped]) => ({ category, items: grouped }));
}

interface InterviewSetupPageProps {
  initialResumeId?: number;
  onStart: (result: InterviewSetupResult) => void;
}

export function canStartInterview(jdText: string, resumeId?: number): boolean {
  return jdText.trim().length >= MIN_JD_CHARS || resumeId != null;
}

export default function InterviewSetupPage({ initialResumeId, onStart }: InterviewSetupPageProps) {
  const [resumes, setResumes] = useState<ResumeListItem[]>([]);
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBaseItem[]>([]);
  const [resumeId, setResumeId] = useState<number | ''>(initialResumeId ?? '');
  const [resumeText, setResumeText] = useState('');
  const [jdText, setJdText] = useState('');
  const [knowledgeBaseIds, setKnowledgeBaseIds] = useState<number[]>([]);
  const [skillId, setSkillId] = useState<string>('java-backend');
  const [questionCount, setQuestionCount] = useState(DEFAULT_QUESTION_COUNT);
  const [loading, setLoading] = useState(true);
  const [loadingResume, setLoadingResume] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    let active = true;
    Promise.allSettled([
      historyApi.getResumes(),
      knowledgeBaseApi.getAllKnowledgeBases('time', 'VECTOR_STORED'),
    ]).then(([resumeResult, knowledgeResult]) => {
      if (!active) return;
      if (resumeResult.status === 'fulfilled') {
        setResumes(resumeResult.value);
      }
      if (knowledgeResult.status === 'fulfilled') {
        setKnowledgeBases(knowledgeResult.value);
      }
      const failed = [resumeResult, knowledgeResult].filter((item) => item.status === 'rejected');
      if (failed.length > 0 && resumeResult.status === 'rejected') {
        setError(getErrorMessage(resumeResult.reason, '简历列表加载失败'));
      }
    }).finally(() => {
      if (active) setLoading(false);
    });
    return () => {
      active = false;
    };
  }, []);

  useEffect(() => {
    if (resumeId === '') {
      setResumeText('');
      return;
    }
    let active = true;
    setLoadingResume(true);
    historyApi.getResumeDetail(resumeId)
      .then((detail) => {
        if (active) setResumeText(detail.resumeText ?? '');
      })
      .catch((reason) => {
        if (active) setError(getErrorMessage(reason, '简历正文加载失败'));
      })
      .finally(() => {
        if (active) setLoadingResume(false);
      });
    return () => {
      active = false;
    };
  }, [resumeId]);

  const selectedResume = useMemo(
    () => resumes.find((item) => item.id === resumeId) ?? null,
    [resumeId, resumes],
  );

  const knowledgeBaseGroups = useMemo(
    () => groupKnowledgeBasesByCategory(knowledgeBases),
    [knowledgeBases],
  );

  const toggleKnowledgeBase = (id: number) => {
    setKnowledgeBaseIds((current) => (
      current.includes(id) ? current.filter((item) => item !== id) : [...current, id]
    ));
  };

  const toggleCategory = (ids: number[]) => {
    setKnowledgeBaseIds((current) => {
      const allSelected = ids.every((id) => current.includes(id));
      if (allSelected) {
        return current.filter((id) => !ids.includes(id));
      }
      return [...new Set([...current, ...ids])];
    });
  };

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault();
    if (!canStartInterview(jdText, resumeId === '' ? undefined : resumeId)) {
      setError('请粘贴至少 50 字的 JD，或选择一份简历。');
      return;
    }
    onStart({
      resumeId: resumeId === '' ? undefined : resumeId,
      resumeText,
      skillId,
      questionCount,
      jdText: jdText.trim(),
      knowledgeBaseIds,
    });
  };

  if (loading) {
    return <LoadingState label="正在加载简历和知识库…" />;
  }

  return (
    <div className="mx-auto max-w-4xl">
      <PageHeader
        title="准备模拟面试"
        description="先贴 JD、选简历和知识库，再开场。Planner 会按这些材料出大纲，而不是通用开场题。"
      />

      {error && (
        <div className="mb-5 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800 dark:border-red-900/60 dark:bg-red-950/30 dark:text-red-300">
          {error}
        </div>
      )}

      <form onSubmit={handleSubmit} className="space-y-5">
        <section className="surface-card p-5">
          <h2 className="text-base font-semibold text-stone-900 dark:text-white">目标岗位 JD</h2>
          <p className="mt-1 text-sm text-stone-500 dark:text-stone-400">
            粘贴岗位职责和任职要求。有 JD 时，大纲会带上岗位重点。
          </p>
          <textarea
            value={jdText}
            onChange={(event) => setJdText(event.target.value)}
            minLength={0}
            maxLength={30000}
            className="dark-input mt-3 min-h-48 w-full p-3 leading-6"
            placeholder="粘贴岗位职责、任职要求和加分项…"
          />
          <p className="mt-2 text-xs text-stone-400">
            {jdText.trim().length} 字
            {jdText.trim().length > 0 && jdText.trim().length < MIN_JD_CHARS
              ? `，至少 ${MIN_JD_CHARS} 字才会作为 JD 使用`
              : ''}
          </p>
        </section>

        <section className="surface-card p-5">
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div>
              <h2 className="text-base font-semibold text-stone-900 dark:text-white">简历</h2>
              <p className="mt-1 text-sm text-stone-500 dark:text-stone-400">
                选中后，出题会读这份简历，追问具体项目而不是让你从零介绍。
              </p>
            </div>
            <Link to="/upload" className="text-sm text-primary-700 hover:underline dark:text-primary-300">
              去上传简历
            </Link>
          </div>
          {resumes.length === 0 ? (
            <EmptyState
              className="mt-4"
              icon={FileText}
              title="还没有简历"
              description="先上传一份，或只靠 JD 开一场通用场。"
            />
          ) : (
            <label className="mt-3 block text-sm text-stone-600 dark:text-stone-300">
              使用的简历
              <select
                value={resumeId}
                onChange={(event) => setResumeId(event.target.value ? Number(event.target.value) : '')}
                className="dark-input mt-1.5 w-full px-3 py-2.5"
              >
                <option value="">不使用简历（通用面试）</option>
                {resumes.map((item) => (
                  <option key={item.id} value={item.id}>{item.filename}</option>
                ))}
              </select>
            </label>
          )}
          {loadingResume && (
            <p className="mt-2 inline-flex items-center gap-2 text-xs text-stone-400">
              <Loader2 className="h-3.5 w-3.5 animate-spin" />正在读取简历正文
            </p>
          )}
          {selectedResume && !loadingResume && (
            <p className="mt-2 text-xs text-stone-400">
              已载入 {resumeText.trim().length} 字正文
            </p>
          )}
        </section>

        <section className="surface-card p-5">
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div>
              <h2 className="text-base font-semibold text-stone-900 dark:text-white">知识库</h2>
              <p className="mt-1 text-sm text-stone-500 dark:text-stone-400">
                可选。按分类勾选后，出题会检索这些资料当证据，而不是整库塞进 Prompt。
              </p>
            </div>
            <Link to="/knowledgebase/upload" className="text-sm text-primary-700 hover:underline dark:text-primary-300">
              去上传资料
            </Link>
          </div>
          {knowledgeBases.length === 0 ? (
            <p className="mt-3 text-sm text-stone-400">还没有已向量化的知识库，可以先不选。</p>
          ) : (
            <div className="mt-3 space-y-4">
              {knowledgeBaseGroups.map((group) => {
                const groupIds = group.items.map((item) => item.id);
                const selectedCount = groupIds.filter((id) => knowledgeBaseIds.includes(id)).length;
                const allSelected = selectedCount === groupIds.length;
                const someSelected = selectedCount > 0 && !allSelected;
                return (
                  <div key={group.category}>
                    <label className="flex cursor-pointer items-center gap-2 text-sm font-medium text-stone-800 dark:text-stone-100">
                      <input
                        type="checkbox"
                        checked={allSelected}
                        ref={(element) => {
                          if (element) element.indeterminate = someSelected;
                        }}
                        onChange={() => toggleCategory(groupIds)}
                        aria-label={`全选 ${group.category}`}
                      />
                      <span>{group.category}</span>
                      <span className="text-xs font-normal text-stone-400">
                        {selectedCount}/{group.items.length}
                      </span>
                    </label>
                    <div className="mt-2 grid gap-2 sm:grid-cols-2">
                      {group.items.map((item) => {
                        const checked = knowledgeBaseIds.includes(item.id);
                        return (
                          <label
                            key={item.id}
                            className={`flex cursor-pointer items-start gap-3 rounded-lg border px-3 py-2.5 text-sm ${
                              checked
                                ? 'border-primary-200 bg-primary-50 dark:border-primary-900 dark:bg-primary-950/30'
                                : 'border-stone-200 dark:border-stone-800'
                            }`}
                          >
                            <input
                              type="checkbox"
                              checked={checked}
                              onChange={() => toggleKnowledgeBase(item.id)}
                              className="mt-0.5"
                            />
                            <span className="min-w-0 truncate font-medium text-stone-800 dark:text-stone-100">
                              {item.name}
                            </span>
                          </label>
                        );
                      })}
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </section>

        <section className="surface-card grid gap-4 p-5 sm:grid-cols-2">
          <label className="text-sm text-stone-600 dark:text-stone-300">面试方向
            <select
              value={skillId}
              onChange={(event) => setSkillId(event.target.value)}
              className="dark-input mt-1.5 w-full px-3 py-2.5"
            >
              {TOPICS.map((topic) => (
                <option key={topic.id} value={topic.id}>{topic.label}</option>
              ))}
            </select>
            <span className="mt-1.5 block text-xs text-stone-400">
              用来选能力目录模板。有 JD 时 Planner 仍以岗位描述为准。
            </span>
          </label>
          <label className="text-sm text-stone-600 dark:text-stone-300">题数
            <input
              type="number"
              min={3}
              max={20}
              value={questionCount}
              onChange={(event) => setQuestionCount(Number(event.target.value))}
              className="dark-input mt-1.5 w-full px-3 py-2.5"
            />
          </label>
        </section>

        <div className="flex flex-wrap items-center justify-end gap-3">
          <button
            type="submit"
            disabled={loadingResume || !canStartInterview(jdText, resumeId === '' ? undefined : resumeId)}
            className="btn-primary inline-flex items-center gap-2 px-4 py-2.5 text-sm disabled:opacity-60"
          >
            开始面试
            <ArrowRight className="h-4 w-4" />
          </button>
        </div>
      </form>
    </div>
  );
}
