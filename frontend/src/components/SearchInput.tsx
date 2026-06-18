import {motion} from 'framer-motion';
import {Search} from 'lucide-react';

interface SearchInputProps {
  value: string;
  onChange: (value: string) => void;
  placeholder: string;
  className?: string;
  iconClassName?: string;
  inputClassName?: string;
  initialX?: number;
  animated?: boolean;
}

export default function SearchInput({
  value,
  onChange,
  placeholder,
  className = 'bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-600 rounded-xl px-4 py-2.5 min-w-[280px] focus-within:border-primary-500 focus-within:ring-2 focus-within:ring-primary-100 dark:focus-within:ring-primary-900/30 transition-all',
  iconClassName = 'w-5 h-5 text-slate-400',
  inputClassName = 'text-slate-700 dark:text-slate-200 placeholder:text-slate-400 bg-transparent',
  initialX = 20,
  animated = true,
}: SearchInputProps) {
  const content = (
    <>
      <Search className={iconClassName} />
      <input
        type="text"
        placeholder={placeholder}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className={`flex-1 outline-none ${inputClassName}`}
      />
    </>
  );
  const containerClassName = `flex items-center gap-3 ${className}`;

  if (!animated) {
    return <div className={containerClassName}>{content}</div>;
  }

  return (
    <motion.div
      className={containerClassName}
      initial={{opacity: 0, x: initialX}}
      animate={{opacity: 1, x: 0}}
    >
      {content}
    </motion.div>
  );
}
