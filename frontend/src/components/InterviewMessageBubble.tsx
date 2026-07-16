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
      <div className="flex items-start gap-3">
        <div className="mt-0.5 flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-lg bg-primary-50 dark:bg-primary-950/50">
          <Bot className="h-4 w-4 text-primary-700 dark:text-primary-300" />
        </div>
        <div className="min-w-0 max-w-[88%] flex-1 sm:max-w-[82%]">
          <div className="flex items-baseline gap-2 mb-1.5">
            <span className="text-[13px] font-semibold text-stone-600 dark:text-stone-300">面试官</span>
            {category && (
              <span className="px-2 py-px bg-primary-50 dark:bg-primary-900/30 text-primary-700 dark:text-primary-300 text-[11px] font-medium rounded-md border border-primary-100 dark:border-primary-800/40">
                {category}
              </span>
            )}
          </div>
          <div
            className={`inline-block rounded-lg px-4 py-3 text-[15px] leading-7
              bg-white dark:bg-stone-900 text-stone-800 dark:text-stone-100
              border ${
                highlight
                  ? 'border-primary-300/70 dark:border-primary-700/50'
                  : 'border-stone-200/80 dark:border-stone-700/60'
              } ${italic ? 'italic text-stone-500 dark:text-stone-400' : ''}`}
          >
            {text}
            {suffix}
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="flex items-start justify-end gap-3">
      <div className="max-w-[88%] sm:max-w-[82%]">
        <div className="flex justify-end mb-1.5">
          <span className="text-[13px] font-semibold text-stone-500 dark:text-stone-400">我</span>
        </div>
        <div
          className={`rounded-lg bg-primary-600 px-4 py-3 text-[15px] leading-7 text-white ${italic ? 'italic' : ''} ${
              highlight ? 'ring-2 ring-primary-300' : ''
            }`}
        >
          {text}
          {suffix}
        </div>
      </div>
      <div className="mt-6 flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-lg bg-stone-200 dark:bg-stone-800">
        <User className="h-4 w-4 text-stone-500 dark:text-stone-300" />
      </div>
    </div>
  );
}
