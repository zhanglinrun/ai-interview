import {ChevronLeft} from 'lucide-react';
import type {ReactNode} from 'react';

interface PageHeaderProps {
  title: string;
  description?: string;
  eyebrow?: string;
  action?: ReactNode;
  onBack?: () => void;
}

export default function PageHeader({title, description, eyebrow, action, onBack}: PageHeaderProps) {
  return (
    <div className="mb-6 flex flex-wrap items-start justify-between gap-4">
      <div className="min-w-0">
        {onBack && (
          <button
            type="button"
            onClick={onBack}
            className="mb-3 inline-flex items-center gap-1 text-sm text-stone-500 transition-colors hover:text-stone-900 dark:text-stone-400 dark:hover:text-stone-200"
          >
            <ChevronLeft className="w-4 h-4" />
            返回
          </button>
        )}
        {eyebrow && (
          <p className="mb-1.5 text-xs font-medium text-stone-500 dark:text-stone-400">
            {eyebrow}
          </p>
        )}
        <h1 className="text-2xl font-semibold text-stone-900 dark:text-stone-50">
          {title}
        </h1>
        {description && (
          <p className="mt-1.5 max-w-2xl text-sm leading-6 text-stone-500 dark:text-stone-400">
            {description}
          </p>
        )}
      </div>
      {action && <div className="shrink-0">{action}</div>}
    </div>
  );
}
