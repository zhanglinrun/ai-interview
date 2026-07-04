import type {ReactNode} from 'react';
import {Check} from 'lucide-react';

interface OptionTileProps {
  selected: boolean;
  onClick: () => void;
  title: string;
  description?: string;
  disabled?: boolean;
  icon?: ReactNode;
}

export default function OptionTile({
  selected,
  onClick,
  title,
  description,
  disabled,
  icon,
}: OptionTileProps) {
  return (
    <button
      type="button"
      disabled={disabled}
      onClick={onClick}
      className={`w-full text-left rounded-xl border p-3.5 transition-all duration-200 disabled:opacity-40 disabled:cursor-not-allowed ${
        selected
          ? 'border-primary-500/60 bg-primary-50/50 dark:bg-primary-950/20 ring-1 ring-primary-500/20'
          : 'border-stone-200 dark:border-stone-700 bg-white dark:bg-stone-900/40 hover:border-stone-300 dark:hover:border-stone-600'
      }`}
    >
      <div className="flex items-start gap-3">
        {icon && (
          <div className={`mt-0.5 shrink-0 ${selected ? 'text-primary-600 dark:text-primary-400' : 'text-stone-400'}`}>
            {icon}
          </div>
        )}
        <div className="flex-1 min-w-0">
          <p className={`text-sm font-medium ${selected ? 'text-stone-900 dark:text-stone-100' : 'text-stone-700 dark:text-stone-300'}`}>
            {title}
          </p>
          {description && (
            <p className="mt-0.5 text-xs text-stone-500 dark:text-stone-400 leading-relaxed">{description}</p>
          )}
        </div>
        <div
          className={`w-5 h-5 rounded-full border flex items-center justify-center shrink-0 transition-colors ${
            selected
              ? 'border-primary-500 bg-primary-500 text-white'
              : 'border-stone-300 dark:border-stone-600'
          }`}
        >
          {selected && <Check className="w-3 h-3" strokeWidth={3} />}
        </div>
      </div>
    </button>
  );
}
