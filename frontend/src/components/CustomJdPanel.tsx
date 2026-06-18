import { AnimatePresence, motion } from 'framer-motion';
import { Sparkles } from 'lucide-react';
import type { CategoryDTO } from '../api/skill';
import LoadingButtonContent from './LoadingButtonContent';

interface CustomJdPanelProps {
  open: boolean;
  value: string;
  onChange: (value: string) => void;
  onParse: () => void;
  parsing: boolean;
  categories: CategoryDTO[];
  needsReparse: boolean;
}

export default function CustomJdPanel({
  open,
  value,
  onChange,
  onParse,
  parsing,
  categories,
  needsReparse,
}: CustomJdPanelProps) {
  return (
    <AnimatePresence>
      {open && (
        <motion.div
          initial={{ height: 0, opacity: 0 }}
          animate={{ height: 'auto', opacity: 1 }}
          exit={{ height: 0, opacity: 0 }}
          className="overflow-hidden"
        >
          <div className="space-y-3 bg-slate-50 dark:bg-slate-900/50 rounded-xl p-4 border border-slate-200 dark:border-slate-700">
            <textarea
              value={value}
              onChange={e => onChange(e.target.value)}
              placeholder="粘贴目标岗位的职位描述（JD），至少 50 字..."
              rows={4}
              className="w-full px-4 py-3 rounded-xl border border-slate-200 dark:border-slate-700
                bg-white dark:bg-slate-800 text-sm text-slate-900 dark:text-white
                placeholder:text-slate-400 resize-none focus:outline-none focus:ring-2
                focus:ring-primary-500/50 focus:border-primary-400 transition-shadow"
            />
            <button
              onClick={onParse}
              disabled={parsing || !value}
              className="flex items-center gap-2 px-4 py-2 text-sm font-medium rounded-lg
                bg-primary-500 text-white hover:bg-primary-600 disabled:opacity-50
                disabled:cursor-not-allowed transition-colors"
            >
              <LoadingButtonContent loading={parsing} loadingText="解析面试方向">
                <span className="inline-flex items-center gap-2">
                  <Sparkles className="w-4 h-4" />
                  解析面试方向
                </span>
              </LoadingButtonContent>
            </button>
            {categories.length > 0 && (
              <div className="flex flex-wrap gap-2">
                {categories.map((cat, index) => (
                  <span
                    key={`${cat.key}-${index}`}
                    className="px-3 py-1 text-xs font-medium rounded-full bg-primary-100 dark:bg-primary-900/30 text-primary-700 dark:text-primary-300"
                  >
                    {cat.label}
                    <span className="ml-1 text-[10px] text-primary-500">({cat.priority})</span>
                  </span>
                ))}
              </div>
            )}
            {needsReparse && (
              <p className="text-xs text-amber-600 dark:text-amber-400">
                JD 已修改，请重新解析后再开始面试。
              </p>
            )}
          </div>
        </motion.div>
      )}
    </AnimatePresence>
  );
}
