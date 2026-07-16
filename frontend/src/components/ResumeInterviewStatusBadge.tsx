interface ResumeInterviewStatusBadgeProps {
  interviewCount: number;
  supportDarkMode?: boolean;
}

export default function ResumeInterviewStatusBadge({
  interviewCount,
  supportDarkMode = true,
}: ResumeInterviewStatusBadgeProps) {
  if (interviewCount > 0) {
    const doneClassName = supportDarkMode
      ? 'inline-flex px-3 py-1 bg-stone-100 dark:bg-stone-800 text-stone-600 dark:text-stone-300 rounded-full text-sm font-medium'
      : 'inline-flex px-3 py-1 bg-stone-100 text-stone-600 rounded-full text-sm font-medium';

    return (
      <span className={doneClassName}>
        {interviewCount} 次面试
      </span>
    );
  }

  const pendingClassName = supportDarkMode
    ? 'inline-flex px-3 py-1 bg-slate-100 dark:bg-slate-700 text-slate-500 dark:text-slate-300 rounded-full text-sm'
    : 'inline-flex px-3 py-1 bg-slate-100 text-slate-500 rounded-full text-sm';

  return <span className={pendingClassName}>暂无记录</span>;
}
