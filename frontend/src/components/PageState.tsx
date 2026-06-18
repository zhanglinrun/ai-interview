import type {ComponentType, ReactNode} from 'react';
import {motion} from 'framer-motion';
import {Loader2} from 'lucide-react';

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
    ?? (compact ? 'text-center py-6' : 'flex flex-col items-center justify-center py-20 gap-3');
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
  className = 'text-center py-20 bg-white dark:bg-slate-800 rounded-2xl shadow-sm border border-slate-100 dark:border-slate-700',
  iconClassName = 'w-16 h-16 text-slate-300 dark:text-slate-600 mx-auto mb-4',
  titleClassName = 'text-xl font-semibold text-slate-700 dark:text-slate-300 mb-2',
  descriptionClassName = 'text-slate-500 dark:text-slate-400',
}: EmptyStateProps) {
  return (
    <motion.div
      className={className}
      initial={{opacity: 0, scale: 0.95}}
      animate={{opacity: 1, scale: 1}}
    >
      {iconNode ?? (Icon ? <Icon className={iconClassName} /> : null)}
      {title && <h3 className={titleClassName}>{title}</h3>}
      {description && <p className={descriptionClassName}>{description}</p>}
      {action}
    </motion.div>
  );
}
