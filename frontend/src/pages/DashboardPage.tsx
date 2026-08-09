import { useEffect, useState } from 'react';
import { ArrowRight, BriefcaseBusiness, Code2, FileText, History } from 'lucide-react';
import { Link } from 'react-router-dom';
import { algorithmApi } from '../api/algorithm';
import { getErrorMessage } from '../api/request';
import { jobTargetApi, type JobTarget } from '../api/jobTarget';
import { interviewApi } from '../api/interview';
import PageHeader from '../components/ui/PageHeader';

interface DashboardStats {
  targets: number;
  problems: number;
  interviews: number;
}

const FLOW_STEPS = [
  ['1', '选择岗位', '粘贴一份真实 JD，明确这次面试要准备什么。'],
  ['2', '补充资料', '简历、项目代码和复习资料都可以按需添加。'],
  ['3', '开始面试', '回答项目追问、岗位技术题和算法题。'],
  ['4', '查看复盘', '回看每道题的表现，再安排下一轮练习。'],
] as const;

const TARGET_STATUS_LABEL: Record<JobTarget['status'], string> = {
  DRAFT: '待分析',
  ANALYZED: '待确认',
  FROZEN: '已准备',
  REDACTED: '已脱敏',
};

export default function DashboardPage() {
  const [stats, setStats] = useState<DashboardStats>({ targets: 0, problems: 0, interviews: 0 });
  const [targets, setTargets] = useState<JobTarget[]>([]);
  const [warning, setWarning] = useState('');

  useEffect(() => {
    let active = true;
    Promise.allSettled([
      jobTargetApi.list(),
      algorithmApi.listProblems(),
      interviewApi.listSessions(),
    ]).then(([targetResult, problemResult, interviewResult]) => {
      if (!active) return;
      const loadedTargets = targetResult.status === 'fulfilled' ? targetResult.value : [];
      setTargets(loadedTargets);
      setStats({
        targets: loadedTargets.length,
        problems: problemResult.status === 'fulfilled' ? problemResult.value.length : 0,
        interviews: interviewResult.status === 'fulfilled' ? interviewResult.value.length : 0,
      });
      const failed = [targetResult, problemResult, interviewResult]
        .filter((result) => result.status === 'rejected');
      if (failed.length > 0) {
        const first = failed[0];
        setWarning(first?.status === 'rejected'
          ? `部分数据暂未加载：${getErrorMessage(first.reason, '服务不可用')}`
          : '部分数据暂未加载');
      }
    });
    return () => {
      active = false;
    };
  }, []);

  const latestTarget = targets[0];
  const statCards = [
    { icon: BriefcaseBusiness, label: '目标岗位', value: stats.targets, note: '已保存' },
    { icon: Code2, label: '算法题', value: stats.problems, note: 'Hot 100' },
    { icon: History, label: '面试记录', value: stats.interviews, note: '已创建' },
  ];

  return (
    <div className="mx-auto max-w-6xl">
      <PageHeader
        title="准备下一场面试"
        description="选择目标岗位，带上你的简历或项目代码，完成一场有针对性的模拟面试。"
        action={(
          <Link to="/job-practice" className="btn-primary inline-flex items-center gap-2 px-4 py-2.5 text-sm">
            开始一场面试
            <ArrowRight className="h-4 w-4" />
          </Link>
        )}
      />

      {warning && (
        <div className="mb-6 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800 dark:border-amber-900/60 dark:bg-amber-950/30 dark:text-amber-300">
          {warning}
        </div>
      )}

      <section className="grid gap-3 sm:grid-cols-3">
        {statCards.map(({ icon: Icon, label, value, note }) => (
          <div key={label} className="surface-card flex items-center gap-4 p-4">
            <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-stone-100 text-stone-600 dark:bg-stone-800 dark:text-stone-300">
              <Icon className="h-5 w-5" />
            </div>
            <div className="min-w-0">
              <p className="text-sm text-stone-500 dark:text-stone-400">{label}</p>
              <p className="mt-0.5 text-xl font-semibold text-stone-900 dark:text-white">{value}<span className="ml-1.5 text-xs font-normal text-stone-400">{note}</span></p>
            </div>
          </div>
        ))}
      </section>

      <section className="mt-5 grid gap-5 lg:grid-cols-[1.35fr_1fr]">
        <div className="surface-card p-5">
          <div className="mb-4 flex items-center gap-2">
            <FileText className="h-5 w-5 text-stone-500" />
            <h2 className="text-base text-stone-900 dark:text-white">使用方法</h2>
          </div>
          <div className="grid gap-3 sm:grid-cols-2">
            {FLOW_STEPS.map(([number, title, description]) => (
              <div key={number} className="rounded-lg border border-stone-200 p-3.5 dark:border-stone-800">
                <div className="mb-2 flex h-6 w-6 items-center justify-center rounded-md bg-primary-50 text-xs font-semibold text-primary-700 dark:bg-primary-950/50 dark:text-primary-300">
                  {number}
                </div>
                <h3 className="text-sm font-semibold text-stone-900 dark:text-white">{title}</h3>
                <p className="mt-1 text-sm leading-5 text-stone-500 dark:text-stone-400">{description}</p>
              </div>
            ))}
          </div>
        </div>

        <div className="surface-card p-5">
          <div className="mb-4 flex items-center gap-2">
            <BriefcaseBusiness className="h-5 w-5 text-stone-500" />
            <h2 className="text-base text-stone-900 dark:text-white">继续准备</h2>
          </div>
          {latestTarget ? (
            <div>
              <span className="inline-flex rounded-md bg-primary-50 px-2 py-1 text-xs font-medium text-primary-700 dark:bg-primary-950/50 dark:text-primary-300">
                {TARGET_STATUS_LABEL[latestTarget.status] ?? latestTarget.status}
              </span>
              <h3 className="mt-3 text-lg font-semibold text-stone-900 dark:text-white">
                {latestTarget.company ? `${latestTarget.company} · ` : ''}{latestTarget.title}
              </h3>
              <p className="mt-2 text-sm text-stone-500 dark:text-stone-400">
                {latestTarget.capabilities.length > 0
                  ? `已整理 ${latestTarget.capabilities.length} 个重点，可以继续准备面试。`
                  : '岗位信息已保存，可以继续补充资料并生成面试。'}
              </p>
              <Link to={`/job-practice?target=${latestTarget.id}`} className="btn-primary mt-6 inline-flex items-center gap-2 px-4 py-2.5 text-sm">
                继续准备
                <ArrowRight className="h-4 w-4" />
              </Link>
            </div>
          ) : (
            <div>
              <h3 className="text-base font-semibold text-stone-900 dark:text-white">先添加一个目标岗位</h3>
              <p className="mt-2 text-sm leading-6 text-stone-500 dark:text-stone-400">
                复制招聘信息里的岗位职责和任职要求，后续问题会围绕它来准备。
              </p>
              <Link to="/job-practice" className="btn-primary mt-6 inline-flex items-center gap-2 px-4 py-2.5 text-sm">
                创建目标岗位
                <ArrowRight className="h-4 w-4" />
              </Link>
            </div>
          )}
        </div>
      </section>
    </div>
  );
}
