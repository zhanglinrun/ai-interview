import { ArrowUpRight, BriefcaseBusiness, ClipboardList, FileSpreadsheet, Info } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import PageHeader from '../components/ui/PageHeader';
import { RECRUITMENT_SOURCES } from '../constants/productLinks';

const SOURCE_ICONS: Record<string, LucideIcon> = {
  'offer-coming': BriefcaseBusiness,
  'kama-delivery': ClipboardList,
  'tencent-sheet': FileSpreadsheet,
};

export default function RecruitmentRadarPage() {
  return (
    <div className="mx-auto max-w-6xl">
      <PageHeader
        title="招聘雷达"
        description="集中查看常用校招信息源。找到合适岗位后，可到模拟面试按简历和主题开一场练习。"
      />

      <p className="mb-4 flex items-center gap-2 text-xs text-stone-500 dark:text-stone-400">
        <Info className="h-3.5 w-3.5 shrink-0" />
        外部信息可能有延迟，投递前请以企业官网为准。
      </p>

      <div className="grid max-w-4xl gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {RECRUITMENT_SOURCES.map((source) => {
          const Icon = SOURCE_ICONS[source.id] ?? BriefcaseBusiness;
          return (
            <a
              key={source.id}
              href={source.url}
              target="_blank"
              rel="noopener noreferrer"
              className="group min-h-36 rounded-xl border border-stone-200 bg-white p-4 shadow-sm transition-[border-color,box-shadow] hover:border-primary-200 hover:shadow-md dark:border-stone-800 dark:bg-stone-900 dark:hover:border-primary-800"
            >
              <div className="flex items-start gap-3">
                <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-stone-100 text-stone-700 dark:bg-stone-800 dark:text-stone-200">
                  <Icon className="h-5 w-5" />
                </div>
                <div className="min-w-0 flex-1 pt-0.5">
                  <div className="flex items-center justify-between gap-2">
                    <h2 className="truncate text-[15px] font-semibold text-stone-900 dark:text-stone-50">{source.name}</h2>
                    <ArrowUpRight className="h-4 w-4 shrink-0 text-stone-300 transition-colors group-hover:text-primary-600 dark:text-stone-600" />
                  </div>
                  <span className="mt-1.5 inline-flex rounded bg-primary-50 px-1.5 py-0.5 text-[11px] font-medium text-primary-700 dark:bg-primary-950/50 dark:text-primary-300">
                    {source.badge}
                  </span>
                </div>
              </div>
              <p className="mt-3 line-clamp-2 text-sm leading-5 text-stone-500 dark:text-stone-400">{source.description}</p>
            </a>
          );
        })}
      </div>
    </div>
  );
}
