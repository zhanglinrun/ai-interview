import { useEffect, useMemo, useState } from 'react';
import { AlertTriangle, CheckCircle2, Clock3, Code2, Loader2, Play, Save, Send } from 'lucide-react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import {
  algorithmApi,
  type CodingAttempt,
  type CodingDraft,
  type CodingLanguage,
  type CodingProblemDetail,
  type JudgeSubmission,
} from '../api/algorithm';
import { getErrorMessage } from '../api/request';
import CodeEditor from '../components/CodeEditor';
import { ErrorState, LoadingState } from '../components/PageState';
import PageHeader from '../components/ui/PageHeader';
import { getAlgorithmTagLabel } from '../utils/displayLabels';

function requestKey(prefix: string): string {
  const suffix = typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  return `${prefix}-${suffix}`;
}

function judgeLabel(status: JudgeSubmission['status']): string {
  const labels: Record<JudgeSubmission['status'], string> = {
    QUEUED: '排队中',
    PROCESSING: '判题中',
    ACCEPTED: '通过',
    WRONG_ANSWER: '答案错误',
    COMPILE_ERROR: '编译错误',
    RUNTIME_ERROR: '运行错误',
    TIME_LIMIT_EXCEEDED: '超出时间限制',
    MEMORY_LIMIT_EXCEEDED: '超出内存限制',
    INTERNAL_ERROR: '判题服务异常',
    UNAVAILABLE: '暂时无法判题',
  };
  return labels[status];
}

const difficultyLabel: Record<CodingProblemDetail['difficulty'], string> = {
  EASY: '简单',
  MEDIUM: '中等',
  HARD: '困难',
};

export default function AlgorithmPracticePage() {
  const { problemVersionId } = useParams<{ problemVersionId: string }>();
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const parsedId = Number(problemVersionId);
  const attemptIdFromUrl = searchParams.get('attemptId')?.trim() ?? '';
  const [problem, setProblem] = useState<CodingProblemDetail | null>(null);
  const [language, setLanguage] = useState<CodingLanguage>('JAVA21');
  const [attempt, setAttempt] = useState<CodingAttempt | null>(null);
  const [draft, setDraft] = useState<CodingDraft | null>(null);
  const [code, setCode] = useState('');
  const [result, setResult] = useState<JudgeSubmission | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  useEffect(() => {
    if (!Number.isInteger(parsedId) || parsedId <= 0) {
      setError('算法题版本参数无效');
      setLoading(false);
      return;
    }
    let active = true;
    setLoading(true);
    setError('');
    setProblem(null);
    setAttempt(null);
    setDraft(null);
    setResult(null);

    const load = async () => {
      const data = await algorithmApi.getProblem(parsedId);
      if (!attemptIdFromUrl) {
        const enabledLanguages = data.languages.map((item) => item.language);
        const defaultLanguage = enabledLanguages.includes('JAVA21')
          ? 'JAVA21'
          : enabledLanguages[0] ?? 'PYTHON3';
        if (!active) return;
        setProblem(data);
        setLanguage(defaultLanguage);
        setCode(data.languages.find((item) => item.language === defaultLanguage)?.template ?? '');
        setNotice('');
        return;
      }

      // getAttempt 由后端按当前用户隔离；前端再校验题目版本，
      // 避免把合法但属于其他题目的 attempt 恢复到当前编辑器。
      const restoredAttempt = await algorithmApi.getAttempt(attemptIdFromUrl);
      if (restoredAttempt.problemVersionId !== parsedId) {
        throw new Error('该作答记录不属于当前算法题');
      }
      const [loadedDraft, submissions] = await Promise.all([
        algorithmApi.getDraft(attemptIdFromUrl),
        algorithmApi.listSubmissions(attemptIdFromUrl),
      ]);
      if (loadedDraft.attemptId !== restoredAttempt.attemptId
          || loadedDraft.language !== restoredAttempt.language) {
        throw new Error('作答记录与草稿信息不一致');
      }
      if (submissions.some((item) => item.attemptId !== restoredAttempt.attemptId)) {
        throw new Error('作答记录与判题结果不一致');
      }
      if (!data.languages.some((item) => item.language === restoredAttempt.language)) {
        throw new Error('当前题目版本已不支持该作答语言');
      }
      if (!active) return;
      setProblem(data);
      setLanguage(restoredAttempt.language);
      setAttempt(restoredAttempt);
      setDraft(loadedDraft);
      setCode(loadedDraft.sourceCode);
      setResult(submissions[0] ?? null);
      setNotice(submissions.length > 0 ? '已恢复代码和最近一次判题结果' : '已恢复上次保存的代码');
    };

    void load()
      .catch((reason) => {
        if (active) setError(getErrorMessage(reason, '题目加载失败'));
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [attemptIdFromUrl, parsedId]);

  const languageTemplate = useMemo(
    () => problem?.languages.find((item) => item.language === language),
    [language, problem],
  );

  const handleLanguageChange = (next: CodingLanguage) => {
    setLanguage(next);
    setCode(problem?.languages.find((item) => item.language === next)?.template ?? '');
    setNotice('');
  };

  const startAttempt = async () => {
    if (!problem) return;
    setBusy('start');
    setError('');
    try {
      const created = await algorithmApi.createAttempt(problem.problemVersionId, language);
      setSearchParams((current) => {
        const next = new URLSearchParams(current);
        next.set('attemptId', created.attemptId);
        return next;
      }, { replace: true });
    } catch (reason) {
      setError(getErrorMessage(reason, '创建作答失败'));
    } finally {
      setBusy('');
    }
  };

  const saveDraft = async () => {
    if (!attempt || !draft || attempt.status === 'COMPLETED' || attempt.status === 'ABORTED') return;
    setBusy('save');
    setError('');
    try {
      const saved = await algorithmApi.saveDraft(attempt.attemptId, code, draft.revision);
      setDraft(saved);
      setNotice('草稿已保存');
    } catch (reason) {
      setError(getErrorMessage(reason, '草稿保存失败，可能已在其他页面更新'));
    } finally {
      setBusy('');
    }
  };

  const judge = async (hidden: boolean) => {
    if (!attempt || attempt.status === 'COMPLETED' || attempt.status === 'ABORTED') return;
    setBusy(hidden ? 'submit' : 'run');
    setError('');
    setNotice('');
    try {
      if (draft) {
        const saved = await algorithmApi.saveDraft(attempt.attemptId, code, draft.revision);
        setDraft(saved);
      }
      const judged = hidden
        ? await algorithmApi.submit(attempt.attemptId, code, requestKey('submit'))
        : await algorithmApi.run(attempt.attemptId, code, requestKey('run'));
      setResult(judged);
      if (judged.status === 'UNAVAILABLE') {
        setNotice('判题服务暂不可用，代码已保存，可以稍后重新提交。');
      }
    } catch (reason) {
      setError(getErrorMessage(reason, '判题请求失败'));
    } finally {
      setBusy('');
    }
  };

  if (loading) return <LoadingState label="加载题目..." />;
  if (error && !problem) {
    return <ErrorState title="题目不可用" description={error} action={(
      <button className="btn-secondary px-4 py-2 text-sm" onClick={() => navigate('/training')}>返回专项训练</button>
    )} />;
  }
  if (!problem) return null;

  return (
    <div className="mx-auto max-w-[1500px]">
      <PageHeader
        eyebrow={`Hot 100 · #${problem.hotRank}`}
        title={problem.title}
        description="先说明思路和复杂度，再编码并运行公开用例；提交后会继续运行隐藏用例。"
        onBack={() => navigate('/training')}
      />

      {error && (
        <div className="mb-4 flex items-start gap-2 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800 dark:border-red-900/60 dark:bg-red-950/30 dark:text-red-300">
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />{error}
        </div>
      )}
      {notice && (
        <div className="mb-4 rounded-xl border border-primary-200 bg-primary-50 px-4 py-3 text-sm text-primary-800 dark:border-primary-900/60 dark:bg-primary-950/30 dark:text-primary-200">
          {notice}
        </div>
      )}

      <div className="grid gap-5 xl:grid-cols-[minmax(360px,0.8fr)_minmax(520px,1.2fr)]">
        <section className="surface-card max-h-[calc(100vh-180px)] overflow-y-auto p-6 scrollbar-thin">
          <div className="flex flex-wrap gap-2">
            <span className="rounded-full bg-stone-100 px-2.5 py-1 text-xs font-medium text-stone-600 dark:bg-stone-800 dark:text-stone-300">{difficultyLabel[problem.difficulty]}</span>
            {problem.tags.map((tag) => (
              <span key={tag} className="rounded-full bg-primary-50 px-2.5 py-1 text-xs text-primary-700 dark:bg-primary-950/40 dark:text-primary-300">
                {getAlgorithmTagLabel(tag)}
              </span>
            ))}
          </div>
          <div className="prose prose-stone mt-6 max-w-none text-sm dark:prose-invert">
            <p className="whitespace-pre-wrap leading-7">{problem.statement}</p>
            <h3>约束</h3>
            <ul>{problem.constraints.map((item) => <li key={item}>{item}</li>)}</ul>
            <h3>公开示例</h3>
            {problem.publicExamples.map((example, index) => (
              <div key={`${example.input}-${index}`} className="not-prose mb-3 rounded-xl bg-stone-100 p-4 text-xs dark:bg-stone-800">
                <p><strong>输入：</strong><code>{example.input}</code></p>
                <p className="mt-2"><strong>输出：</strong><code>{example.output}</code></p>
                {example.explanation && <p className="mt-2 text-stone-500">{example.explanation}</p>}
              </div>
            ))}
            {problem.complexityRubric && (
              <>
                <h3>复杂度目标</h3>
                <p>时间 {problem.complexityRubric.expectedTime}，空间 {problem.complexityRubric.expectedSpace}</p>
              </>
            )}
          </div>
        </section>

        <section className="surface-card overflow-hidden">
          <div className="flex flex-wrap items-center justify-between gap-3 border-b border-stone-200/80 px-5 py-4 dark:border-stone-800">
            <div className="flex items-center gap-2">
              <Code2 className="h-5 w-5 text-primary-600" />
              <span className="font-medium text-stone-900 dark:text-white">代码作答</span>
              {draft && <span className="text-xs text-stone-400">已保存</span>}
            </div>
            <select
              value={language}
              disabled={attempt !== null}
              onChange={(event) => handleLanguageChange(event.target.value as CodingLanguage)}
              className="dark-input px-3 py-2 text-sm disabled:cursor-not-allowed disabled:opacity-60"
            >
              {problem.languages.map((item) => (
                <option key={item.language} value={item.language}>{item.language === 'JAVA21' ? 'Java' : 'Python 3'}</option>
              ))}
            </select>
          </div>
          <div className="border-b border-stone-200/80 bg-stone-950 px-4 py-2 text-xs text-stone-400 dark:border-stone-800">
            {languageTemplate?.functionSignature || '按题目模板完成函数'}
          </div>
          <CodeEditor
            value={code}
            onChange={setCode}
            language={language === 'JAVA21' ? 'java' : 'python'}
            ariaLabel="算法专项代码编辑器"
            height={460}
            readOnly={attempt?.status === 'COMPLETED' || attempt?.status === 'ABORTED'}
          />
          <div className="flex flex-wrap items-center gap-2 border-t border-stone-200/80 p-4 dark:border-stone-800">
            {!attempt ? (
              <button onClick={startAttempt} disabled={Boolean(busy)} className="btn-primary inline-flex items-center gap-2 px-4 py-2.5 text-sm disabled:opacity-60">
                {busy === 'start' ? <Loader2 className="h-4 w-4 animate-spin" /> : <Clock3 className="h-4 w-4" />}
                开始作答
              </button>
            ) : attempt.status === 'COMPLETED' || attempt.status === 'ABORTED' ? (
              <p className="text-sm text-stone-500 dark:text-stone-400">
                {attempt.status === 'COMPLETED'
                  ? '本次作答已完成，代码和判题结果仅供回看。'
                  : '本次作答已结束，代码仅供回看。'}
              </p>
            ) : (
              <>
                <button onClick={saveDraft} disabled={Boolean(busy)} className="btn-secondary inline-flex items-center gap-2 px-3 py-2 text-sm disabled:opacity-60">
                  {busy === 'save' ? <Loader2 className="h-4 w-4 animate-spin" /> : <Save className="h-4 w-4" />}保存草稿
                </button>
                <button onClick={() => judge(false)} disabled={Boolean(busy)} className="btn-secondary inline-flex items-center gap-2 px-3 py-2 text-sm disabled:opacity-60">
                  {busy === 'run' ? <Loader2 className="h-4 w-4 animate-spin" /> : <Play className="h-4 w-4" />}运行公开用例
                </button>
                <button onClick={() => judge(true)} disabled={Boolean(busy)} className="btn-primary ml-auto inline-flex items-center gap-2 px-4 py-2 text-sm disabled:opacity-60">
                  {busy === 'submit' ? <Loader2 className="h-4 w-4 animate-spin" /> : <Send className="h-4 w-4" />}正式提交
                </button>
              </>
            )}
          </div>

          {result && (
            <div className={`m-4 rounded-xl border p-4 ${
              result.status === 'ACCEPTED'
                ? 'border-emerald-200 bg-emerald-50 dark:border-emerald-900/60 dark:bg-emerald-950/30'
                : 'border-amber-200 bg-amber-50 dark:border-amber-900/60 dark:bg-amber-950/30'
            }`}>
              <div className="flex items-center gap-2">
                {result.status === 'ACCEPTED'
                  ? <CheckCircle2 className="h-5 w-5 text-emerald-600" />
                  : <AlertTriangle className="h-5 w-5 text-amber-600" />}
                <span className="font-semibold text-stone-900 dark:text-white">{judgeLabel(result.status)}</span>
                <span className="ml-auto text-xs text-stone-500">{result.suiteType === 'PUBLIC' ? '公开用例' : '隐藏用例'}</span>
              </div>
              <p className="mt-2 text-sm text-stone-600 dark:text-stone-300">
                {result.status === 'UNAVAILABLE'
                  ? '未执行判题，代码已保存'
                  : result.passedCount != null && result.totalCount != null
                  ? `通过 ${result.passedCount}/${result.totalCount}`
                  : '当前没有可展示的用例计数'}
                {result.timeMs != null ? ` · ${result.timeMs} ms` : ''}
                {result.memoryKb != null ? ` · ${result.memoryKb} KB` : ''}
              </p>
              {result.diagnostic && <pre className="mt-3 overflow-auto whitespace-pre-wrap rounded-lg bg-black/5 p-3 text-xs dark:bg-black/20">{result.diagnostic}</pre>}
            </div>
          )}
        </section>
      </div>
    </div>
  );
}
