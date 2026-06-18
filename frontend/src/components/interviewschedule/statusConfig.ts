import type {InterviewStatus} from '../../types/interviewSchedule';

export function isPendingScheduleStatus(status: InterviewStatus): boolean {
  return status === 'PENDING';
}

export const scheduleStatusBadgeConfig: Record<InterviewStatus, {
  label: string;
  className: string;
}> = {
  PENDING: {
    label: '待面试',
    className: 'bg-blue-500/10 dark:bg-blue-500/20 text-blue-700 dark:text-blue-300 border border-blue-300/30 dark:border-blue-400/30',
  },
  COMPLETED: {
    label: '已完成',
    className: 'bg-emerald-500/10 dark:bg-emerald-500/20 text-emerald-700 dark:text-emerald-300 border border-emerald-300/30 dark:border-emerald-400/30',
  },
  CANCELLED: {
    label: '已取消',
    className: 'bg-slate-500/10 dark:bg-slate-500/20 text-slate-700 dark:text-slate-300 border border-slate-300/30 dark:border-slate-400/30',
  },
  RESCHEDULED: {
    label: '已改期',
    className: 'bg-amber-500/10 dark:bg-amber-500/20 text-amber-700 dark:text-amber-300 border border-amber-300/30 dark:border-amber-400/30',
  },
};

export const scheduleEventStatusConfig: Record<InterviewStatus, {
  bg: string;
  text: string;
  border: string;
  shadow: string;
}> = {
  PENDING: {
    bg: 'bg-blue-100/90 dark:bg-blue-500/25',
    text: 'text-blue-900 dark:text-blue-100',
    border: 'border-blue-300/60 dark:border-blue-400/40',
    shadow: 'shadow-blue-200/60 dark:shadow-blue-500/20',
  },
  COMPLETED: {
    bg: 'bg-emerald-100/90 dark:bg-emerald-500/25',
    text: 'text-emerald-900 dark:text-emerald-100',
    border: 'border-emerald-300/60 dark:border-emerald-400/40',
    shadow: 'shadow-emerald-200/60 dark:shadow-emerald-500/20',
  },
  CANCELLED: {
    bg: 'bg-slate-100/90 dark:bg-slate-500/25',
    text: 'text-slate-700 dark:text-slate-200',
    border: 'border-slate-300/60 dark:border-slate-400/40',
    shadow: 'shadow-slate-200/60 dark:shadow-slate-500/20',
  },
  RESCHEDULED: {
    bg: 'bg-amber-100/90 dark:bg-amber-500/25',
    text: 'text-amber-900 dark:text-amber-100',
    border: 'border-amber-300/60 dark:border-amber-400/40',
    shadow: 'shadow-amber-200/60 dark:shadow-amber-500/20',
  },
};
