import { useEffect, useMemo, useState } from 'react';
import { AlertTriangle, ArrowRight, CheckCircle2, ClipboardCheck, Loader2, Play } from 'lucide-react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { reportApi, type TrainingTask } from '../api/report';
import { getErrorMessage } from '../api/request';

const typeLabel: Record<TrainingTask['trainingType'], string> = {
  ALGORITHM: '算法训练',
  PROJECT_DEEP_DIVE: '项目深挖',
  TECHNICAL_FOUNDATION: '技术基础',
  ENGINEERING_SCENARIO: '工程场景',
};

const statusLabel: Record<TrainingTask['status'], string> = {
  RECOMMENDED: '推荐',
  IN_PROGRESS: '进行中',
  COMPLETED: '已完成',
};

const sourceLabel: Record<string, string> = {
  PLATFORM: '平台题库',
  JOB: '岗位要求',
  CANDIDATE: '个人资料',
  GITHUB: 'GitHub 项目',
};

export default function TrainingTaskPanel() {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const [tasks, setTasks] = useState<TrainingTask[]>([]);
  const [selectedId, setSelectedId] = useState(searchParams.get('task') ?? '');
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');
  const [form, setForm] = useState({
    score: 70,
    objectivePassed: false,
    hintUsed: false,
    answerViewed: false,
    observation: '',
  });

  const selected = useMemo(
    () => tasks.find((task) => task.taskId === selectedId) ?? tasks.find((task) => task.status !== 'COMPLETED') ?? tasks[0] ?? null,
    [selectedId, tasks],
  );
  const sourceReportDate = selected?.reportId && selected.createdAt
    ? new Date(selected.createdAt).toLocaleDateString() : null;

  const load = async () => {
    try {
      const loaded = await reportApi.listTrainingTasks();
      setTasks(loaded);
      const requested = searchParams.get('task');
      if (requested && loaded.some((task) => task.taskId === requested)) setSelectedId(requested);
    } catch (reason) {
      setError(getErrorMessage(reason, '训练任务加载失败'));
    }
  };

  useEffect(() => {
    void load();
    // 查询参数仅用于首次定位报告推荐的任务。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (!selected) return;
    setForm({
      score: selected.resultScore ?? 70,
      objectivePassed: false,
      hintUsed: selected.hintUsed,
      answerViewed: selected.answerViewed,
      observation: '',
    });
  }, [selected?.taskId]);

  const selectTask = (taskId: string) => {
    setSelectedId(taskId);
    setSearchParams({ task: taskId }, { replace: true });
    setError('');
  };

  const start = async (task: TrainingTask) => {
    setBusy('start');
    try {
      const updated = await reportApi.recordTrainingInteraction(task.taskId, {
        hintUsed: false,
        answerViewed: false,
        redo: false,
      });
      setTasks((current) => current.map((item) => item.taskId === updated.taskId ? updated : item));
      navigate(trainingHref(task));
    } catch (reason) {
      setError(getErrorMessage(reason, '训练任务启动失败'));
    } finally {
      setBusy('');
    }
  };

  const trainingHref = (task: TrainingTask) => task.trainingType === 'ALGORITHM'
    ? '/training#hot100'
    : `/knowledgebase/chat?question=${encodeURIComponent(task.question)}`;

  const complete = async (task: TrainingTask) => {
    setBusy('complete');
    setError('');
    try {
      const updated = await reportApi.completeTrainingTask(task.taskId, {
        score: form.score,
        objectivePassed: form.objectivePassed,
        hintUsed: form.hintUsed,
        answerViewed: form.answerViewed,
        redoCount: task.redoCount,
        observation: form.observation.trim() || undefined,
      });
      setTasks((current) => current.map((item) => item.taskId === updated.taskId ? updated : item));
    } catch (reason) {
      setError(getErrorMessage(reason, '训练任务完成失败'));
    } finally {
      setBusy('');
    }
  };

  if (tasks.length === 0 && !error) return null;

  return (
    <section className="surface-card mb-8 overflow-hidden">
      <div className="border-b border-stone-200/80 p-5 dark:border-stone-800">
        <div className="flex items-center gap-2"><ClipboardCheck className="h-5 w-5 text-primary-600" /><h2 className="text-lg font-semibold text-stone-900 dark:text-white">报告推荐训练</h2></div>
        <p className="mt-1 text-sm text-stone-500 dark:text-stone-400">这里汇总面试报告推荐的练习，也可以记录你主动完成的训练。</p>
      </div>
      {error && <div className="m-4 flex items-start gap-2 rounded-xl bg-red-50 p-3 text-sm text-red-700 dark:bg-red-950/30 dark:text-red-300"><AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />{error}</div>}
      {tasks.length > 0 && selected && (
        <div className="grid lg:grid-cols-[300px_minmax(0,1fr)]">
          <div className="max-h-96 divide-y divide-stone-100 overflow-y-auto border-r border-stone-200/80 dark:divide-stone-800 dark:border-stone-800 scrollbar-thin">
            {tasks.map((task) => (
              <button key={task.taskId} onClick={() => selectTask(task.taskId)} className={`w-full p-4 text-left ${selected.taskId === task.taskId ? 'bg-primary-50 dark:bg-primary-950/30' : 'hover:bg-stone-50 dark:hover:bg-stone-900/50'}`}>
                <div className="flex items-center justify-between gap-2"><span className="text-xs font-medium text-primary-700 dark:text-primary-300">{typeLabel[task.trainingType]}</span><span className="text-[11px] text-stone-400">{statusLabel[task.status]}</span></div>
                <p className="mt-2 line-clamp-2 text-sm text-stone-700 dark:text-stone-200">{task.question}</p>
              </button>
            ))}
          </div>
          <div className="p-6">
            <div className="flex flex-wrap items-center gap-2"><span className="rounded-full bg-primary-50 px-2.5 py-1 text-xs font-medium text-primary-700 dark:bg-primary-950/40 dark:text-primary-300">{typeLabel[selected.trainingType]}</span>{selected.evidenceScopes.map((scope) => <span key={scope} className="rounded-full bg-stone-100 px-2 py-1 text-xs text-stone-500 dark:bg-stone-800 dark:text-stone-300">{sourceLabel[scope] ?? scope}</span>)}{sourceReportDate && <span className="text-xs text-stone-400">来自 {sourceReportDate} 的面试报告</span>}</div>
            <h3 className="mt-4 text-lg font-semibold leading-7 text-stone-900 dark:text-white">{selected.question}</h3>
            {selected.status === 'RECOMMENDED' && <button onClick={() => void start(selected)} disabled={Boolean(busy)} className="btn-primary mt-5 inline-flex items-center gap-2 px-4 py-2.5 text-sm">{busy === 'start' ? <Loader2 className="h-4 w-4 animate-spin" /> : <Play className="h-4 w-4" />}开始训练</button>}
            {selected.status === 'IN_PROGRESS' && (
              <div className="mt-5">
                <div className="flex flex-wrap gap-3">
                  <Link to={trainingHref(selected)} className="btn-secondary inline-flex items-center gap-2 px-3 py-2 text-sm">
                    {selected.trainingType === 'ALGORITHM' ? '选择一道算法题' : '用资料问答练习'}
                    <ArrowRight className="h-4 w-4" />
                  </Link>
                </div>
                <div className="mt-5 grid gap-3 sm:grid-cols-2">
                  <label className="text-sm text-stone-600 dark:text-stone-300">本次自评（0-100）<input type="number" min={0} max={100} value={form.score} onChange={(event) => setForm({ ...form, score: Math.min(100, Math.max(0, Number(event.target.value))) })} className="dark-input mt-1 w-full px-3 py-2" /></label>
                  <label className="flex items-end gap-2 pb-2 text-sm text-stone-600 dark:text-stone-300"><input type="checkbox" checked={form.objectivePassed} onChange={(event) => setForm({ ...form, objectivePassed: event.target.checked })} className="accent-primary-600" />这次能够独立回答</label>
                  <label className="flex items-center gap-2 text-sm text-stone-600 dark:text-stone-300"><input type="checkbox" checked={form.hintUsed} onChange={(event) => setForm({ ...form, hintUsed: event.target.checked })} className="accent-primary-600" />使用过提示</label>
                  <label className="flex items-center gap-2 text-sm text-stone-600 dark:text-stone-300"><input type="checkbox" checked={form.answerViewed} onChange={(event) => setForm({ ...form, answerViewed: event.target.checked })} className="accent-primary-600" />查看过答案</label>
                </div>
                <textarea value={form.observation} onChange={(event) => setForm({ ...form, observation: event.target.value })} maxLength={500} className="dark-input mt-3 min-h-20 w-full p-3 text-sm" placeholder="记录本次仍卡住的点（可选）" />
                <p className="mt-2 text-xs leading-5 text-stone-400">这是个人复习记录，不等同于平台判分；使用提示或查看答案后不会记为独立完成。</p>
                <button onClick={() => void complete(selected)} disabled={Boolean(busy)} className="btn-primary mt-4 inline-flex items-center gap-2 px-4 py-2.5 text-sm">{busy === 'complete' ? <Loader2 className="h-4 w-4 animate-spin" /> : <CheckCircle2 className="h-4 w-4" />}完成本次训练</button>
              </div>
            )}
            {selected.status === 'COMPLETED' && <div className="mt-5 rounded-xl bg-emerald-50 p-4 text-sm text-emerald-800 dark:bg-emerald-950/30 dark:text-emerald-300"><div className="flex items-center gap-2 font-medium"><CheckCircle2 className="h-4 w-4" />已记录本次复习 · 自评 {selected.resultScore ?? '未填写'} 分</div><p className="mt-1 text-xs opacity-80">{selected.hintUsed || selected.answerViewed || selected.redoCount > 0 ? '本次记录为参考练习。' : '本次记录为独立完成。'}</p></div>}
          </div>
        </div>
      )}
    </section>
  );
}
