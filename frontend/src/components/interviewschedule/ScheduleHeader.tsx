// frontend/src/components/interviewschedule/ScheduleHeader.tsx

import React from 'react';
import { Plus, ChevronLeft, ChevronRight, Calendar, List, LayoutGrid } from 'lucide-react';
import dayjs from 'dayjs';

export type ScheduleView = 'day' | 'week' | 'month' | 'list';

interface ScheduleHeaderProps {
  view: ScheduleView;
  onViewChange: (view: ScheduleView) => void;
  date: Date;
  onDateChange: (date: Date) => void;
  onAddClick: () => void;
}

const VIEW_OPTIONS: Array<{
  key: ScheduleView;
  icon: typeof Calendar;
  label: string;
}> = [
  { key: 'day', icon: Calendar, label: '日视图' },
  { key: 'week', icon: Calendar, label: '周视图' },
  { key: 'month', icon: LayoutGrid, label: '月视图' },
  { key: 'list', icon: List, label: '列表' },
];

export const ScheduleHeader: React.FC<ScheduleHeaderProps> = ({
  view,
  onViewChange,
  date,
  onDateChange,
  onAddClick,
}) => {
  const handlePrevious = () => {
    const newDate = new Date(date);
    if (view === 'day') {
      newDate.setDate(newDate.getDate() - 1);
    } else if (view === 'week') {
      newDate.setDate(newDate.getDate() - 7);
    } else if (view === 'month') {
      newDate.setMonth(newDate.getMonth() - 1);
    }
    onDateChange(newDate);
  };

  const handleNext = () => {
    const newDate = new Date(date);
    if (view === 'day') {
      newDate.setDate(newDate.getDate() + 1);
    } else if (view === 'week') {
      newDate.setDate(newDate.getDate() + 7);
    } else if (view === 'month') {
      newDate.setMonth(newDate.getMonth() + 1);
    }
    onDateChange(newDate);
  };

  const handleToday = () => {
    onDateChange(new Date());
  };

  const getTitle = () => {
    if (view === 'list') {
      return '面试列表';
    }
    return dayjs(date).format(view === 'month' ? 'YYYY年MM月' : 'YYYY年MM月DD日');
  };

  return (
    <div className="surface-card flex flex-col gap-4 p-4 lg:flex-row lg:items-center lg:justify-between">
      <div className="flex flex-wrap items-center gap-3">
        <h2 className="min-w-36 text-lg font-semibold text-stone-900 dark:text-stone-50">
          {getTitle()}
        </h2>

        {view !== 'list' && (
          <div className="flex items-center gap-1">
            <button
              type="button"
              onClick={handlePrevious}
              className="btn-secondary p-2"
              aria-label="上一页"
            >
              <ChevronLeft className="h-4 w-4" />
            </button>
            <button
              type="button"
              onClick={handleToday}
              className="btn-secondary px-3 py-2 text-sm"
            >
              今天
            </button>
            <button
              type="button"
              onClick={handleNext}
              className="btn-secondary p-2"
              aria-label="下一页"
            >
              <ChevronRight className="h-4 w-4" />
            </button>
          </div>
        )}
      </div>

      <div className="flex flex-wrap items-center gap-2">
        <div className="flex flex-wrap gap-1 rounded-lg bg-stone-100 p-1 dark:bg-stone-800">
          {VIEW_OPTIONS.map(({ key, icon: Icon, label }) => (
            <button
              key={key}
              type="button"
              onClick={() => onViewChange(key)}
              className={`flex items-center gap-1.5 rounded-md px-2.5 py-1.5 text-sm font-medium transition-colors ${
                view === key
                  ? 'bg-white text-primary-700 shadow-sm dark:bg-stone-700 dark:text-primary-200'
                  : 'text-stone-600 hover:bg-white/70 hover:text-stone-900 dark:text-stone-300 dark:hover:bg-stone-700 dark:hover:text-white'
              }`}
              aria-pressed={view === key}
            >
              <Icon className="h-4 w-4" />
              {label}
            </button>
          ))}
        </div>

        <button
          type="button"
          onClick={onAddClick}
          className="btn-primary flex items-center gap-2 px-3.5 py-2 text-sm"
        >
          <Plus className="h-4 w-4" />
          新建日程
        </button>
      </div>
    </div>
  );
};
