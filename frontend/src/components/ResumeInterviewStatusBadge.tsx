import {CheckCircle2} from 'lucide-react';

interface ResumeInterviewStatusBadgeProps {
  interviewCount: number;
  completedLabel?: string;
  supportDarkMode?: boolean;
}

export default function ResumeInterviewStatusBadge({
  interviewCount,
  completedLabel,
  supportDarkMode = true,
}: ResumeInterviewStatusBadgeProps) {
  if (interviewCount > 0) {
    const doneClassName = supportDarkMode
      ? 'inline-flex items-center gap-1.5 px-3 py-1 bg-emerald-50 dark:bg-emerald-900 text-emerald-600 rounded-full text-sm font-medium'
      : 'inline-flex items-center gap-1.5 px-3 py-1 bg-emerald-50 text-emerald-600 rounded-full text-sm font-medium';

    return (
      <span className={doneClassName}>
        <CheckCircle2 className="w-4 h-4" />
        {completedLabel ?? `${interviewCount} 次`}
      </span>
    );
  }

  const pendingClassName = supportDarkMode
    ? 'inline-flex px-3 py-1 bg-slate-100 dark:bg-slate-700 text-slate-500 dark:text-slate-300 rounded-full text-sm'
    : 'inline-flex px-3 py-1 bg-slate-100 text-slate-500 rounded-full text-sm';

  return <span className={pendingClassName}>待面试</span>;
}
