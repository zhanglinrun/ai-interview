import { useEffect, useMemo, useState } from 'react';
import { ArrowRight, BrainCircuit, Code2, Database, ExternalLink, Search } from 'lucide-react';
import { Link } from 'react-router-dom';
import { algorithmApi, type CodingLanguage, type CodingProblemSummary } from '../api/algorithm';
import { getErrorMessage } from '../api/request';
import { EmptyState, LoadingState } from '../components/PageState';
import PageHeader from '../components/ui/PageHeader';
import TrainingTaskPanel from '../components/TrainingTaskPanel';
import { getAlgorithmTagLabel } from '../utils/displayLabels';

const difficultyLabel: Record<CodingProblemSummary['difficulty'], string> = {
  EASY: '简单',
  MEDIUM: '中等',
  HARD: '困难',
};

export default function TrainingPage() {
  const [problems, setProblems] = useState<CodingProblemSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [query, setQuery] = useState('');
  const [language, setLanguage] = useState<CodingLanguage | 'ALL'>('ALL');

  useEffect(() => {
    let active = true;
    setLoading(true);
    algorithmApi.listProblems(language === 'ALL' ? undefined : { language })
      .then((items) => {
        if (!active) return;
        setProblems(items);
        setError('');
      })
      .catch((reason) => {
        if (!active) return;
        setError(getErrorMessage(reason, '题库加载失败'));
        setProblems([]);
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [language]);

  const filteredProblems = useMemo(() => {
    const normalized = query.trim().toLowerCase();
    if (!normalized) return problems;
    return problems.filter((problem) =>
      problem.title.toLowerCase().includes(normalized)
      || problem.tags.some((tag) => tag.toLowerCase().includes(normalized)
        || getAlgorithmTagLabel(tag).toLowerCase().includes(normalized)),
    );
  }, [problems, query]);

  const onlineProblemCount = useMemo(
    () => problems.filter((problem) => problem.problemVersionId !== null).length,
    [problems],
  );

  return (
    <div className="mx-auto max-w-7xl">
      <PageHeader
        title="专项训练"
        description="按需要练算法、复习资料或检查 RAG 效果。"
      />

      <div className="mb-5 grid gap-3 md:grid-cols-3">
        <div className="surface-card flex gap-3 border-primary-200 p-4 dark:border-primary-900/50">
          <Code2 className="mt-0.5 h-5 w-5 shrink-0 text-primary-600" />
          <div><h2 className="text-sm font-semibold text-stone-900 dark:text-white">Hot 100 算法</h2>
          <p className="mt-1 text-sm leading-5 text-stone-500 dark:text-stone-400">完整收录 100 道题；已接入题目支持 Java/Python 3 在线作答，其余跳转力扣题面。</p></div>
        </div>
        <Link to="/knowledgebase/chat" className="surface-card hover-card flex gap-3 p-4">
          <Database className="mt-0.5 h-5 w-5 shrink-0 text-primary-600" />
          <div><h2 className="text-sm font-semibold text-stone-900 dark:text-white">资料问答</h2>
          <p className="mt-1 text-sm leading-5 text-stone-500 dark:text-stone-400">选择复习资料提问，回答会标出引用来源。</p></div>
        </Link>
        <Link to="/eval" className="surface-card hover-card flex gap-3 p-4">
          <BrainCircuit className="mt-0.5 h-5 w-5 shrink-0 text-primary-600" />
          <div><h2 className="text-sm font-semibold text-stone-900 dark:text-white">RAG 评测</h2>
          <p className="mt-1 text-sm leading-5 text-stone-500 dark:text-stone-400">用测试集检查检索和回答效果。</p></div>
        </Link>
      </div>

      <TrainingTaskPanel />

      <section id="hot100" className="surface-card overflow-hidden">
        <div className="flex flex-wrap items-center justify-between gap-3 border-b border-stone-200/80 p-5 dark:border-stone-800">
          <div>
            <h2 className="text-lg font-semibold text-stone-900 dark:text-white">平台精选 Hot 100</h2>
            <p className="mt-1 text-sm text-stone-500 dark:text-stone-400">
              当前收录 {problems.length} 道题，其中 {onlineProblemCount} 道支持平台内在线作答，其余可打开 LeetCode 官方题面。
            </p>
          </div>
          <div className="flex w-full flex-wrap gap-2 sm:w-auto">
            <label className="relative flex-1 sm:w-56">
              <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-stone-400" />
              <input
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder="搜索题目或标签"
                className="dark-input w-full py-2 pl-9 pr-3 text-sm"
              />
            </label>
            <select
              value={language}
              onChange={(event) => setLanguage(event.target.value as CodingLanguage | 'ALL')}
              className="dark-input px-3 py-2 text-sm"
            >
              <option value="ALL">全部语言</option>
              <option value="JAVA21">Java</option>
              <option value="PYTHON3">Python 3</option>
            </select>
          </div>
        </div>

        {loading ? (
          <LoadingState label="加载算法题库..." />
        ) : error ? (
          <EmptyState
            icon={Code2}
            title="题库暂不可用"
            description={error}
          />
        ) : filteredProblems.length === 0 ? (
          <EmptyState icon={Search} title="没有匹配题目" description="调整关键词或语言筛选后重试。" />
        ) : (
          <div className="divide-y divide-stone-100 dark:divide-stone-800">
            {filteredProblems.map((problem) => {
              const row = (
                <>
                  <span className="w-8 text-sm font-medium text-stone-400">#{problem.hotRank}</span>
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-2">
                      <h3 className="font-medium text-stone-900 dark:text-white">{problem.title}</h3>
                      <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${
                        problem.difficulty === 'EASY'
                          ? 'bg-emerald-50 text-emerald-700 dark:bg-emerald-950/40 dark:text-emerald-300'
                          : problem.difficulty === 'MEDIUM'
                            ? 'bg-amber-50 text-amber-700 dark:bg-amber-950/40 dark:text-amber-300'
                            : 'bg-red-50 text-red-700 dark:bg-red-950/40 dark:text-red-300'
                      }`}>{difficultyLabel[problem.difficulty]}</span>
                    </div>
                    <div className="mt-1 flex flex-wrap gap-1.5">
                      {problem.tags.slice(0, 4).map((tag) => (
                        <span key={tag} className="text-xs text-stone-400">
                          {getAlgorithmTagLabel(tag)}
                        </span>
                      ))}
                    </div>
                  </div>
                  <span className="text-xs text-stone-400">
                    {problem.problemVersionId !== null
                      ? problem.enabledLanguages.map((item) => item === 'JAVA21' ? 'Java' : 'Python 3').join(' / ')
                      : '打开力扣题面'}
                  </span>
                  {problem.problemVersionId !== null
                    ? <ArrowRight className="h-4 w-4 text-stone-300 transition-transform group-hover:translate-x-1 group-hover:text-primary-600" />
                    : <ExternalLink className="h-4 w-4 text-stone-300 transition-colors group-hover:text-primary-600" />}
                </>
              );
              const rowClassName = "group flex flex-wrap items-center gap-4 px-5 py-4 transition-colors hover:bg-primary-50/50 dark:hover:bg-primary-950/20";
              return problem.problemVersionId !== null ? (
                <Link
                  key={problem.problemId}
                  to={`/training/algorithm/${problem.problemVersionId}`}
                  className={rowClassName}
                >
                  {row}
                </Link>
              ) : (
                <a
                  key={problem.problemId}
                  href={problem.sourceUrl ?? undefined}
                  target="_blank"
                  rel="noreferrer"
                  className={rowClassName}
                  aria-label={`${problem.title}（打开力扣题面）`}
                >
                  {row}
                </a>
              );
            })}
          </div>
        )}
      </section>
    </div>
  );
}
