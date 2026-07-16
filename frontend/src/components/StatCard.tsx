export interface StatCardProps {
  icon: React.ComponentType<{ className?: string }>;
  label: string;
  value: number | string;
  suffix?: string;
  color: string;
  supportDarkMode?: boolean;
}

export default function StatCard({
  icon: Icon,
  label,
  value,
  suffix,
  color,
  supportDarkMode = true,
}: StatCardProps) {
  const cardClass = supportDarkMode
    ? 'surface-card p-4'
    : 'rounded-xl border border-stone-200 bg-white p-4';
  const labelClass = supportDarkMode
    ? 'text-xs text-stone-500 dark:text-stone-400'
    : 'text-xs text-stone-500';
  const valueClass = supportDarkMode
    ? 'text-xl font-semibold text-stone-900 dark:text-white'
    : 'text-xl font-semibold text-stone-900';
  const suffixClass = supportDarkMode
    ? 'text-base font-normal text-slate-400 dark:text-slate-500 ml-1'
    : 'text-base font-normal text-slate-400 ml-1';
  const displayValue = typeof value === 'number' ? value.toLocaleString() : value;

  return (
    <div className={cardClass}>
      <div className="flex items-center gap-3">
        <div className={`rounded-lg p-2.5 ${color}`}>
          <Icon className="h-4 w-4 text-white" />
        </div>
        <div>
          <p className={labelClass}>{label}</p>
          <p className={valueClass}>
            {displayValue}
            {suffix && <span className={suffixClass}>{suffix}</span>}
          </p>
        </div>
      </div>
    </div>
  );
}
