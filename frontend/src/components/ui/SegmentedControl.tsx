interface SegmentedOption<T extends string> {
  value: T;
  label: string;
  disabled?: boolean;
}

interface SegmentedControlProps<T extends string> {
  value: T;
  onChange: (value: T) => void;
  options: SegmentedOption<T>[];
  className?: string;
}

export default function SegmentedControl<T extends string>({
  value,
  onChange,
  options,
  className = '',
}: SegmentedControlProps<T>) {
  return (
    <div
      className={`inline-flex p-1 rounded-lg bg-stone-100 dark:bg-stone-800/80 border border-stone-200/80 dark:border-stone-700/80 ${className}`}
      role="tablist"
    >
      {options.map((opt) => {
        const active = value === opt.value;
        return (
          <button
            key={opt.value}
            type="button"
            role="tab"
            aria-selected={active}
            disabled={opt.disabled}
            onClick={() => onChange(opt.value)}
            className={`px-4 py-2 text-sm font-medium rounded-md transition-all duration-200 disabled:opacity-40 disabled:cursor-not-allowed ${
              active
                ? 'bg-white dark:bg-stone-900 text-stone-900 dark:text-stone-100 shadow-sm'
                : 'text-stone-500 dark:text-stone-400 hover:text-stone-700 dark:hover:text-stone-200'
            }`}
          >
            {opt.label}
          </button>
        );
      })}
    </div>
  );
}
