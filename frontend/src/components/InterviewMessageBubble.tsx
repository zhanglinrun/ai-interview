import { motion } from 'framer-motion';
import { Bot, User } from 'lucide-react';
import type { ReactNode } from 'react';

export type InterviewMessageRole = 'interviewer' | 'user';

interface InterviewMessageBubbleProps {
  role: InterviewMessageRole;
  text: string;
  category?: string;
  highlight?: boolean;
  italic?: boolean;
  suffix?: ReactNode;
}

export default function InterviewMessageBubble({
  role,
  text,
  category,
  highlight = false,
  italic = false,
  suffix,
}: InterviewMessageBubbleProps) {
  if (role === 'interviewer') {
    return (
      <motion.div
        initial={{ opacity: 0, y: 8 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.25, ease: 'easeOut' }}
        className="flex items-start gap-3"
      >
        <div className="w-9 h-9 rounded-xl bg-gradient-to-br from-primary-500 to-primary-700 shadow-sm shadow-primary-500/20 flex items-center justify-center flex-shrink-0 mt-0.5">
          <Bot className="w-[18px] h-[18px] text-white" />
        </div>
        <div className="flex-1 min-w-0 max-w-[85%]">
          <div className="flex items-baseline gap-2 mb-1.5">
            <span className="text-[13px] font-semibold text-stone-600 dark:text-stone-300">面试官</span>
            {category && (
              <span className="px-2 py-px bg-primary-50 dark:bg-primary-900/30 text-primary-700 dark:text-primary-300 text-[11px] font-medium rounded-md border border-primary-100 dark:border-primary-800/40">
                {category}
              </span>
            )}
          </div>
          <div
            className={`inline-block rounded-2xl rounded-tl-md px-4 py-3 text-[15px] leading-7
              bg-white dark:bg-stone-900 text-stone-800 dark:text-stone-100
              border shadow-[0_1px_3px_rgba(0,0,0,0.04)] ${
                highlight
                  ? 'border-primary-300/70 dark:border-primary-700/50'
                  : 'border-stone-200/80 dark:border-stone-700/60'
              } ${italic ? 'italic text-stone-500 dark:text-stone-400' : ''}`}
          >
            {text}
            {suffix}
          </div>
        </div>
      </motion.div>
    );
  }

  return (
    <motion.div
      initial={{ opacity: 0, y: 8 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.25, ease: 'easeOut' }}
      className="flex items-start gap-3 justify-end"
    >
      <div className="max-w-[85%]">
        <div className="flex justify-end mb-1.5">
          <span className="text-[13px] font-semibold text-stone-500 dark:text-stone-400">我</span>
        </div>
        <div
          className={`rounded-2xl rounded-tr-md px-4 py-3 text-[15px] leading-7
            bg-gradient-to-br from-primary-600 to-primary-700 text-white
            shadow-[0_2px_8px_rgba(13,148,136,0.25)] ${italic ? 'italic' : ''} ${
              highlight ? 'ring-2 ring-primary-300/50' : ''
            }`}
        >
          {text}
          {suffix}
        </div>
      </div>
      <div className="w-9 h-9 rounded-xl bg-stone-200 dark:bg-stone-700 flex items-center justify-center flex-shrink-0 mt-6">
        <User className="w-[18px] h-[18px] text-stone-500 dark:text-stone-300" />
      </div>
    </motion.div>
  );
}
