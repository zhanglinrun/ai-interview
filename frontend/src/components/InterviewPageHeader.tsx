import type { ReactNode } from 'react';

interface InterviewPageHeaderProps {
  title: string;
  subtitle: string;
  icon: ReactNode;
}

export default function InterviewPageHeader({
  title,
  subtitle,
  icon,
}: InterviewPageHeaderProps) {
  return (
    <div className="mb-6 flex items-center gap-3">
      <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-primary-50 text-primary-700 dark:bg-primary-950/50 dark:text-primary-300">
        {icon}
      </div>
      <div>
        <h1 className="text-xl font-bold text-stone-900 dark:text-stone-50 leading-tight">{title}</h1>
        <p className="text-sm text-stone-500 dark:text-stone-400 mt-0.5">{subtitle}</p>
      </div>
    </div>
  );
}
