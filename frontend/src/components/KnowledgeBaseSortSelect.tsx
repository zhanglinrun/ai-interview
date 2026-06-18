import { ChevronDown } from 'lucide-react';
import type { SortOption } from '../api/knowledgebase';

const SORT_OPTIONS = [
  { value: 'time', defaultLabel: '按时间排序', compactLabel: '时间排序' },
  { value: 'size', defaultLabel: '按大小排序', compactLabel: '大小排序' },
  { value: 'access', defaultLabel: '按访问排序', compactLabel: '访问排序' },
  { value: 'question', defaultLabel: '按提问排序', compactLabel: '提问排序' },
] satisfies Array<{
  value: SortOption;
  defaultLabel: string;
  compactLabel: string;
}>;

const toSortOption = (value: string): SortOption => {
  if (value === 'size' || value === 'access' || value === 'question') {
    return value;
  }
  return 'time';
};

interface KnowledgeBaseSortSelectProps {
  value: SortOption;
  onChange: (value: SortOption) => void;
  compact?: boolean;
}

export default function KnowledgeBaseSortSelect({
  value,
  onChange,
  compact = false,
}: KnowledgeBaseSortSelectProps) {
  const selectClassName = compact
    ? 'w-full px-2 py-1 text-xs border border-slate-200 dark:border-slate-600 rounded-lg focus:outline-none focus:ring-1 focus:ring-primary-500 bg-white dark:bg-slate-700 text-slate-700 dark:text-slate-300'
    : 'appearance-none pl-4 pr-10 py-2 border border-slate-200 dark:border-slate-600 rounded-lg focus:outline-none focus:ring-2 focus:ring-primary-500 bg-white dark:bg-slate-700 text-slate-900 dark:text-white cursor-pointer';

  return (
    <div className={compact ? undefined : 'relative'}>
      <select
        value={value}
        onChange={(event) => onChange(toSortOption(event.target.value))}
        className={selectClassName}
      >
        {SORT_OPTIONS.map(option => (
          <option key={option.value} value={option.value}>
            {compact ? option.compactLabel : option.defaultLabel}
          </option>
        ))}
      </select>
      {!compact && (
        <ChevronDown className="absolute right-3 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400 pointer-events-none" />
      )}
    </div>
  );
}
