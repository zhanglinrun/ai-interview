import type { ReactNode } from 'react';
import { motion } from 'framer-motion';

interface InterviewPageHeaderProps {
  title: string;
  subtitle: string;
  icon: ReactNode;
}

export default function InterviewPageHeader({
  title,
  subtitle,
  icon,
}: InterviewPageHeaderProps) {
  return (
    <motion.div
      className="flex items-center gap-3.5 mb-6"
      initial={{ opacity: 0, y: -12 }}
      animate={{ opacity: 1, y: 0 }}
    >
      <div className="w-11 h-11 bg-gradient-to-br from-primary-500 to-primary-700 rounded-xl flex items-center justify-center shadow-sm shadow-primary-500/25">
        {icon}
      </div>
      <div>
        <h1 className="text-xl font-bold text-stone-900 dark:text-stone-50 leading-tight">{title}</h1>
        <p className="text-sm text-stone-500 dark:text-stone-400 mt-0.5">{subtitle}</p>
      </div>
    </motion.div>
  );
}
