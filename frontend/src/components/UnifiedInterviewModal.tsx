import { useEffect } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import {
  X, Sparkles,
} from 'lucide-react';
import { useInterviewConfig, DIFFICULTY_OPTIONS, type InterviewMode, type Difficulty } from '../hooks/useInterviewConfig';
import CustomJdPanel from './CustomJdPanel';
import InterviewAdvancedOptions from './InterviewAdvancedOptions';
import InterviewDifficultySelector from './InterviewDifficultySelector';
import InterviewModeSelector from './InterviewModeSelector';
import InterviewSkillSelector from './InterviewSkillSelector';

// Re-export for backward compatibility
export type { InterviewMode, Difficulty };
export { DIFFICULTY_OPTIONS };

export interface UnifiedInterviewConfig {
  mode: InterviewMode;
  skillId: string;
  skillName: string;
  difficulty: Difficulty;
  resumeId?: number;
  resumeText?: string;
  llmProvider: string;
  questionCount: number;
  techEnabled: boolean;
  projectEnabled: boolean;
  hrEnabled: boolean;
  plannedDuration: number;
  customJdText?: string;
  customCategories?: import('../api/skill').CategoryDTO[];
}

interface UnifiedInterviewModalProps {
  isOpen: boolean;
  onClose: () => void;
  onStart: (config: UnifiedInterviewConfig) => void;
  defaultMode?: InterviewMode;
  defaultResumeId?: number;
  hideModeSwitch?: boolean;
  title?: string;
  subtitle?: string;
  startButtonText?: string;
}

export default function UnifiedInterviewModal({
  isOpen,
  onClose,
  onStart,
  defaultMode = 'text',
  defaultResumeId,
  hideModeSwitch = false,
  title = '开始模拟面试',
  subtitle = '选择面试模式和主题，快速开始',
  startButtonText = '开始面试',
}: UnifiedInterviewModalProps) {
  const config = useInterviewConfig({ defaultMode, defaultResumeId, autoLoad: false });
  const {
    loadResumes,
    loadSkills,
    setMode,
    setResumeId,
    setShowMore,
  } = config;

  useEffect(() => {
    if (isOpen) {
      setMode(defaultMode);
      if (defaultResumeId != null) {
        setResumeId(defaultResumeId);
        setShowMore(true);
      }
      loadSkills();
      loadResumes();
    }
  }, [
    defaultMode,
    defaultResumeId,
    isOpen,
    loadResumes,
    loadSkills,
    setMode,
    setResumeId,
    setShowMore,
  ]);

  const handleStart = () => {
    const selectedSkill = config.selectedSkill;

    if (config.isCustomStartDisabled) {
      return;
    }

    onStart({
      mode: config.mode,
      skillId: config.skillId,
      skillName: selectedSkill?.name || '自定义',
      difficulty: config.difficulty,
      resumeId: config.resumeId,
      llmProvider: config.llmProvider,
      questionCount: config.questionCount,
      techEnabled: true,
      projectEnabled: true,
      hrEnabled: true,
      plannedDuration: config.plannedDuration,
      customJdText: config.isCustomSkill ? config.parsedCustomJdText : undefined,
      customCategories: config.isCustomSkill ? config.customCategories : undefined,
    });
  };

  if (!isOpen) return null;

  return (
    <AnimatePresence>
      {isOpen && (
        <>
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            onClick={onClose}
            className="fixed inset-0 bg-black/60 backdrop-blur-sm z-50"
          />
          <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
            <motion.div
              initial={{ opacity: 0, scale: 0.95, y: 20 }}
              animate={{ opacity: 1, scale: 1, y: 0 }}
              exit={{ opacity: 0, scale: 0.95, y: 20 }}
              onClick={e => e.stopPropagation()}
              className="bg-white dark:bg-slate-800 rounded-2xl shadow-2xl max-w-2xl w-full max-h-[90vh] overflow-y-auto"
            >
              {/* Header */}
              <div className="px-6 py-5 border-b border-slate-100 dark:border-slate-700/50">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-3">
                    <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-primary-500 to-primary-600 flex items-center justify-center shadow-lg shadow-primary-500/25">
                      <Sparkles className="w-5 h-5 text-white" />
                    </div>
                    <div>
                      <h2 className="text-lg font-bold text-slate-900 dark:text-white">
                        {title}
                      </h2>
                      <p className="text-xs text-slate-500 dark:text-slate-400">
                        {subtitle}
                      </p>
                    </div>
                  </div>
                  <button
                    onClick={onClose}
                    className="p-2 text-slate-400 hover:text-slate-600 dark:hover:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-700 rounded-lg transition-colors"
                  >
                    <X className="w-5 h-5" />
                  </button>
                </div>
              </div>

              {/* Content */}
              <div className="px-6 py-5 space-y-5">
                {!hideModeSwitch && (
                  <InterviewModeSelector
                    value={config.mode}
                    onChange={config.setMode}
                    compact
                  />
                )}

                <InterviewSkillSelector
                  skills={config.skills}
                  loading={config.loadingSkills}
                  value={config.skillId}
                  onChange={config.setSkillId}
                  isCustomSkill={config.isCustomSkill}
                  compact
                />

                <CustomJdPanel
                  open={config.isCustomSkill}
                  value={config.customJdText}
                  onChange={config.setCustomJdText}
                  onParse={config.handleParseJd}
                  parsing={config.parsingJd}
                  categories={config.customCategories}
                  needsReparse={config.jdNeedsReparse}
                />

                {/* 难度 */}
                <InterviewDifficultySelector
                  value={config.difficulty}
                  onChange={config.setDifficulty}
                  compact
                />

                <InterviewAdvancedOptions
                  mode={config.mode}
                  showMore={config.showMore}
                  onShowMoreChange={config.setShowMore}
                  resumeId={config.resumeId}
                  onResumeChange={config.setResumeId}
                  resumes={config.resumes}
                  questionCount={config.questionCount}
                  onQuestionCountChange={config.setQuestionCount}
                  plannedDuration={config.plannedDuration}
                  onPlannedDurationChange={config.setPlannedDuration}
                />
              </div>

              {/* Footer */}
              <div className="px-6 py-4 bg-slate-50/80 dark:bg-slate-900/50 border-t border-slate-100 dark:border-slate-700/50 rounded-b-2xl">
                <div className="flex gap-3">
                  <motion.button
                    onClick={onClose}
                    whileHover={{ scale: 1.02 }}
                    whileTap={{ scale: 0.98 }}
                    className="flex-1 px-5 py-3 border border-slate-200 dark:border-slate-700
                      text-slate-700 dark:text-slate-300 rounded-xl font-medium text-sm
                      hover:bg-slate-100 dark:hover:bg-slate-800 transition-all"
                  >
                    取消
                  </motion.button>
                  <motion.button
                    onClick={handleStart}
                    whileHover={{ scale: 1.02 }}
                    whileTap={{ scale: 0.98 }}
                    disabled={config.isCustomStartDisabled}
                    className="flex-1 px-5 py-3 rounded-xl font-semibold text-sm transition-all
                      bg-gradient-to-r from-primary-500 to-primary-600 hover:from-primary-600 hover:to-primary-700
                      text-white shadow-lg shadow-primary-500/25 disabled:opacity-50 disabled:cursor-not-allowed"
                  >
                    {startButtonText}
                  </motion.button>
                </div>
              </div>
            </motion.div>
          </div>
        </>
      )}
    </AnimatePresence>
  );
}
