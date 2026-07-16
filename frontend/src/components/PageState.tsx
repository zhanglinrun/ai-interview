import type {ComponentType, ReactNode} from 'react';
import {AlertCircle, Loader2} from 'lucide-react';

interface LoadingStateProps {
  label?: ReactNode;
  description?: ReactNode;
  className?: string;
  spinnerClassName?: string;
  textClassName?: string;
  descriptionClassName?: string;
  compact?: boolean;
}

export function LoadingState({
  label,
  description,
  className,
  spinnerClassName,
  textClassName = 'text-slate-500 dark:text-slate-400',
  descriptionClassName = 'text-slate-400 text-sm',
  compact = false,
}: LoadingStateProps) {
  const resolvedClassName = className
    ?? (compact ? 'text-center py-6' : 'flex flex-col items-center justify-center py-12 gap-3');
  const resolvedSpinnerClassName = spinnerClassName
    ?? (compact
      ? 'w-5 h-5 text-primary-500 animate-spin mx-auto'
      : 'w-8 h-8 text-primary-500 animate-spin');

  return (
    <div className={resolvedClassName}>
      <Loader2 className={resolvedSpinnerClassName} />
      {label && <p className={textClassName}>{label}</p>}
      {description && <p className={descriptionClassName}>{description}</p>}
    </div>
  );
}

interface EmptyStateProps {
  icon?: ComponentType<{ className?: string }>;
  iconNode?: ReactNode;
  title?: ReactNode;
  description?: ReactNode;
  action?: ReactNode;
  className?: string;
  iconClassName?: string;
  titleClassName?: string;
  descriptionClassName?: string;
}

export function EmptyState({
  icon: Icon,
  iconNode,
  title,
  description,
  action,
  className = 'surface-card px-5 py-12 text-center',
  iconClassName = 'mx-auto mb-3 h-10 w-10 text-stone-300 dark:text-stone-600',
  titleClassName = 'mb-1 text-base font-semibold text-stone-700 dark:text-stone-300',
  descriptionClassName = 'text-sm text-stone-500 dark:text-stone-400',
}: EmptyStateProps) {
  return (
    <div className={className}>
      {iconNode ?? (Icon ? <Icon className={iconClassName} /> : null)}
      {title && <h3 className={titleClassName}>{title}</h3>}
      {description && <p className={descriptionClassName}>{description}</p>}
      {action}
    </div>
  );
}

interface ErrorStateProps extends Omit<EmptyStateProps, 'icon' | 'iconNode'> {
  title?: ReactNode;
}

export function ErrorState({
  title = '加载失败',
  description,
  action,
  className = 'min-h-[50vh] flex flex-col items-center justify-center text-center',
  iconClassName = 'w-12 h-12 text-red-500 dark:text-red-400 mb-4',
  titleClassName = 'text-lg font-semibold text-slate-700 dark:text-slate-300 mb-2',
  descriptionClassName = 'text-sm text-slate-500 dark:text-slate-400 mb-6',
}: ErrorStateProps) {
  return (
    <EmptyState
      icon={AlertCircle}
      title={title}
      description={description}
      action={action}
      className={className}
      iconClassName={iconClassName}
      titleClassName={titleClassName}
      descriptionClassName={descriptionClassName}
    />
  );
}
