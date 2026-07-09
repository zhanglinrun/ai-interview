import { useEffect } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import {
  X,
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
  knowledgeBaseIds?: number[];
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
    loadKnowledgeBases,
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
      loadKnowledgeBases();
    }
  }, [
    defaultMode,
    defaultResumeId,
    isOpen,
    loadKnowledgeBases,
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
      knowledgeBaseIds: config.selectedKbIds.length > 0 ? config.selectedKbIds : undefined,
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
              className="surface-card max-w-2xl w-full max-h-[90vh] overflow-y-auto"
            >
              {/* Header */}
              <div className="px-6 py-5 border-b border-stone-200/80 dark:border-stone-800">
                <div className="flex items-center justify-between">
                  <div>
                    <h2 className="text-lg font-semibold text-stone-900 dark:text-stone-50">
                      {title}
                    </h2>
                    <p className="text-sm text-stone-500 dark:text-stone-400 mt-0.5">
                      {subtitle}
                    </p>
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
                  knowledgeBases={config.knowledgeBases}
                  loadingKnowledgeBases={config.loadingKnowledgeBases}
                  selectedKbIds={config.selectedKbIds}
                  onKnowledgeBaseToggle={config.toggleKnowledgeBase}
                  llmProvider={config.llmProvider}
                  onLlmProviderChange={config.setLlmProvider}
                />
              </div>

              {/* Footer */}
              <div className="px-6 py-4 bg-stone-50/80 dark:bg-stone-900/40 border-t border-stone-200/80 dark:border-stone-800 rounded-b-2xl">
                <div className="flex gap-3">
                  <motion.button
                    onClick={onClose}
                    whileHover={{ scale: 1.01 }}
                    whileTap={{ scale: 0.99 }}
                    className="flex-1 px-5 py-3 btn-secondary rounded-xl font-medium text-sm"
                  >
                    取消
                  </motion.button>
                  <motion.button
                    onClick={handleStart}
                    whileHover={{ scale: 1.01 }}
                    whileTap={{ scale: 0.99 }}
                    disabled={config.isCustomStartDisabled}
                    className="flex-1 px-5 py-3 rounded-xl font-medium text-sm btn-primary disabled:opacity-50 disabled:cursor-not-allowed"
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
