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
    <div className="mb-8 flex flex-wrap items-start justify-between gap-4">
      <div className="min-w-0">
        {onBack && (
          <button
            type="button"
            onClick={onBack}
            className="mb-3 inline-flex items-center gap-1.5 text-sm text-stone-500 hover:text-stone-800 dark:text-stone-400 dark:hover:text-stone-200 transition-colors"
          >
            <ChevronLeft className="w-4 h-4" />
            返回
          </button>
        )}
        {eyebrow && (
          <p className="text-xs font-medium tracking-wide text-primary-600 dark:text-primary-400 mb-1.5">
            {eyebrow}
          </p>
        )}
        <h1 className="text-2xl md:text-3xl font-display font-semibold text-stone-900 dark:text-stone-50 tracking-tight">
          {title}
        </h1>
        {description && (
          <p className="mt-2 text-sm md:text-base text-stone-500 dark:text-stone-400 max-w-2xl leading-relaxed">
            {description}
          </p>
        )}
      </div>
      {action && <div className="shrink-0">{action}</div>}
    </div>
  );
}
