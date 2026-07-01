import { AnimatePresence, motion } from 'framer-motion';
import { ChevronDown, ChevronUp, FileStack } from 'lucide-react';
import type { ResumeListItem } from '../api/history';
import type { KnowledgeBaseItem } from '../api/knowledgebase';
import type { InterviewMode } from '../hooks/useInterviewConfig';
import InterviewKnowledgeBaseSelector from './InterviewKnowledgeBaseSelector';

const QUESTION_COUNTS = [6, 8, 10, 12] as const;

interface InterviewAdvancedOptionsProps {
  mode: InterviewMode;
  showMore: boolean;
  onShowMoreChange: (showMore: boolean) => void;
  resumeId: number | undefined;
  onResumeChange: (resumeId: number | undefined) => void;
  resumes: ResumeListItem[];
  questionCount: number;
  onQuestionCountChange: (questionCount: number) => void;
  plannedDuration: number;
  onPlannedDurationChange: (plannedDuration: number) => void;
  knowledgeBases?: KnowledgeBaseItem[];
  loadingKnowledgeBases?: boolean;
  selectedKbIds?: number[];
  onKnowledgeBaseToggle?: (id: number) => void;
}

export default function InterviewAdvancedOptions({
  mode,
  showMore,
  onShowMoreChange,
  resumeId,
  onResumeChange,
  resumes,
  questionCount,
  onQuestionCountChange,
  plannedDuration,
  onPlannedDurationChange,
  knowledgeBases = [],
  loadingKnowledgeBases = false,
  selectedKbIds = [],
  onKnowledgeBaseToggle,
}: InterviewAdvancedOptionsProps) {
  return (
    <>
      <button
        onClick={() => onShowMoreChange(!showMore)}
        className="w-full flex items-center gap-2 py-2 text-sm text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-300 transition-colors"
      >
        {showMore ? <ChevronUp className="w-4 h-4" /> : <ChevronDown className="w-4 h-4" />}
        <span>更多选项</span>
        <div className="flex-1 border-t border-slate-200 dark:border-slate-700" />
      </button>

      <AnimatePresence>
        {showMore && (
          <motion.div
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: 'auto', opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            className="overflow-hidden space-y-4"
          >
            <div className="bg-gradient-to-br from-primary-50/80 to-blue-50/80 dark:from-primary-900/20 dark:to-blue-900/10 rounded-xl p-4 border border-primary-100 dark:border-primary-800/30">
              <div className="flex items-center gap-3 mb-3">
                <FileStack className="w-5 h-5 text-primary-500" />
                <p className="font-semibold text-sm text-primary-900 dark:text-primary-100">
                  基于简历面试（可选）
                </p>
              </div>
              <select
                value={resumeId || ''}
                onChange={e => onResumeChange(e.target.value ? parseInt(e.target.value) : undefined)}
                className="w-full px-4 py-2.5 rounded-lg border border-primary-200 dark:border-primary-700/50
                  bg-white dark:bg-slate-800 text-sm text-slate-900 dark:text-white
                  focus:outline-none focus:ring-2 focus:ring-primary-500/50 transition-shadow"
              >
                <option value="">不使用简历（通用提问）</option>
                {resumes.map(r => (
                  <option key={r.id} value={r.id}>{r.filename}</option>
                ))}
              </select>
            </div>

            {mode === 'text' && onKnowledgeBaseToggle && (
              <InterviewKnowledgeBaseSelector
                knowledgeBases={knowledgeBases}
                loading={loadingKnowledgeBases}
                selectedIds={selectedKbIds}
                onToggle={onKnowledgeBaseToggle}
              />
            )}

            {mode === 'text' && (
              <div>
                <label className="flex items-center gap-2 mb-3 text-sm font-semibold text-slate-700 dark:text-slate-200">
                  题目数量
                </label>
                <div className="flex gap-2">
                  {QUESTION_COUNTS.map(n => (
                    <button
                      key={n}
                      onClick={() => onQuestionCountChange(n)}
                      className={`flex-1 py-2 rounded-lg text-sm font-medium transition-all
                        ${questionCount === n
                          ? 'bg-primary-500 text-white shadow-sm'
                          : 'bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300 hover:bg-slate-200 dark:hover:bg-slate-600'
                        }`}
                    >
                      {n} 题
                    </button>
                  ))}
                </div>
              </div>
            )}

            {mode === 'voice' && (
              <div className="bg-slate-50/80 dark:bg-slate-900/50 rounded-xl p-4 border border-slate-200 dark:border-slate-700">
                <div className="flex items-center justify-between mb-3">
                  <p className="font-semibold text-sm text-slate-900 dark:text-white">计划面试时长</p>
                  <div className="text-2xl font-bold tabular-nums text-primary-600 dark:text-primary-400">
                    {plannedDuration}
                    <span className="text-xs font-normal text-slate-400 ml-0.5">min</span>
                  </div>
                </div>
                <input
                  type="range"
                  min="15"
                  max="60"
                  step="5"
                  value={plannedDuration}
                  onChange={e => onPlannedDurationChange(parseInt(e.target.value))}
                  className="w-full h-2 bg-slate-200 dark:bg-slate-700 rounded-lg appearance-none cursor-pointer
                    [&::-webkit-slider-thumb]:appearance-none [&::-webkit-slider-thumb]:w-4
                    [&::-webkit-slider-thumb]:h-4 [&::-webkit-slider-thumb]:rounded-full
                    [&::-webkit-slider-thumb]:bg-primary-500 [&::-webkit-slider-thumb]:cursor-pointer
                    [&::-webkit-slider-thumb]:shadow-md [&::-webkit-slider-thumb]:shadow-primary-500/30"
                />
              </div>
            )}
          </motion.div>
        )}
      </AnimatePresence>
    </>
  );
}
