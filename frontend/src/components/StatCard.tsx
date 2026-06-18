import {motion} from 'framer-motion';

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
    ? 'bg-white dark:bg-slate-800 rounded-xl p-6 shadow-sm border border-slate-100 dark:border-slate-700'
    : 'bg-white rounded-xl p-6 shadow-sm border border-slate-100';
  const labelClass = supportDarkMode
    ? 'text-sm text-slate-500 dark:text-slate-400'
    : 'text-sm text-slate-500';
  const valueClass = supportDarkMode
    ? 'text-2xl font-bold text-slate-800 dark:text-white'
    : 'text-2xl font-bold text-slate-800';
  const suffixClass = supportDarkMode
    ? 'text-base font-normal text-slate-400 dark:text-slate-500 ml-1'
    : 'text-base font-normal text-slate-400 ml-1';
  const displayValue = typeof value === 'number' ? value.toLocaleString() : value;

  return (
    <motion.div
      initial={{ opacity: 0, y: 20 }}
      animate={{ opacity: 1, y: 0 }}
      className={cardClass}
    >
      <div className="flex items-center gap-4">
        <div className={`p-3 rounded-lg ${color}`}>
          <Icon className="w-6 h-6 text-white" />
        </div>
        <div>
          <p className={labelClass}>{label}</p>
          <p className={valueClass}>
            {displayValue}
            {suffix && <span className={suffixClass}>{suffix}</span>}
          </p>
        </div>
      </div>
    </motion.div>
  );
}
