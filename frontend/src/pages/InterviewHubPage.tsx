import { useState, useEffect, useCallback } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { motion } from 'framer-motion';
import {
  ChevronRight,
  FileText,
  Mic,
} from 'lucide-react';
import { type SkillDTO } from '../api/skill';
import { interviewApi, type TextSessionMeta } from '../api/interview';
import { voiceInterviewApi, type SessionMeta } from '../api/voiceInterview';
import type {EvaluateStatus} from '../api/history';
import { getTemplateName } from '../utils/voiceInterview';
import { getScoreTextColor } from '../utils/score';
import { compareDateDesc, formatDateTime } from '../utils/date';
import { isEvaluationCompleted, isEvaluationProcessing } from '../utils/interviewStatus';
import CustomJdPanel from '../components/CustomJdPanel';
import CandidateMemoryPanel from '../components/CandidateMemoryPanel';
import InterviewAdvancedOptions from '../components/InterviewAdvancedOptions';
import InterviewDifficultySelector from '../components/InterviewDifficultySelector';
import InterviewModeSelector from '../components/InterviewModeSelector';
import InterviewSkillSelector from '../components/InterviewSkillSelector';
import InterviewStatusBadge from '../components/InterviewStatusBadge';
import { EmptyState, LoadingState } from '../components/PageState';
import PageHeader from '../components/ui/PageHeader';
import { useInterviewConfig } from '../hooks/useInterviewConfig';

// 统一的面试记录项
interface RecentInterviewItem {
  id: string;
  type: 'text' | 'voice';
  title: string;
  status: string;
  evaluateStatus?: EvaluateStatus | null;
  overallScore: number | null;
  createdAt: string;
  voiceSessionId?: number;
}

export default function InterviewHubPage() {
  const navigate = useNavigate();

  const config = useInterviewConfig({ autoLoad: false });
  const { loadSkills, loadResumes } = config;

  // === 最近面试记录 ===
  const [recentInterviews, setRecentInterviews] = useState<RecentInterviewItem[]>([]);
  const [loadingRecent, setLoadingRecent] = useState(false);

  const loadRecentInterviews = useCallback(async (allSkills: SkillDTO[]) => {
    setLoadingRecent(true);
    try {
      const [textSessions, voiceSessions] = await Promise.all([
        interviewApi.listSessions().catch((): TextSessionMeta[] => []),
        voiceInterviewApi.getAllSessions().catch((): SessionMeta[] => []),
      ]);

      const items: RecentInterviewItem[] = [
        ...textSessions.map(s => ({
          id: s.sessionId,
          type: 'text' as const,
          title: getTemplateName(s.skillId, allSkills),
          status: s.status,
          evaluateStatus: s.evaluateStatus,
          overallScore: s.overallScore,
          createdAt: s.createdAt,
        })),
        ...voiceSessions.map(s => ({
          id: `voice-${s.sessionId}`,
          type: 'voice' as const,
          title: s.roleType || '语音面试',
          status: s.status,
          overallScore: null,
          createdAt: s.createdAt,
          voiceSessionId: s.sessionId,
        })),
      ];

      items.sort((a, b) => compareDateDesc(a.createdAt, b.createdAt));
      setRecentInterviews(items.slice(0, 5));
    } catch (err) {
      console.error('Failed to load recent interviews:', err);
    } finally {
      setLoadingRecent(false);
    }
  }, []);

  // 初始加载：skills 和 resumes 并行，再用 skills 加载面试记录
  useEffect(() => {
    const init = async () => {
      const [skills] = await Promise.all([loadSkills(), loadResumes()]);
      await loadRecentInterviews(skills);
    };
    init();
  }, [loadRecentInterviews, loadResumes, loadSkills]);

  const handleStart = () => {
    const selectedSkill = config.selectedSkill;
    const skillName = selectedSkill?.name || '自定义';

    if (config.isCustomStartDisabled) {
      return;
    }

    if (config.mode === 'text') {
      navigate('/interview', {
        state: {
          resumeId: config.resumeId,
          interviewConfig: {
            skillId: config.skillId,
            skillName,
            difficulty: config.difficulty,
            questionCount: config.questionCount,
            llmProvider: config.llmProvider,
            jdText: config.isCustomSkill ? config.parsedCustomJdText : undefined,
            customCategories: config.isCustomSkill ? config.customCategories : undefined,
            knowledgeBaseIds: config.selectedKbIds.length > 0 ? config.selectedKbIds : undefined,
          },
        },
      });
    } else {
      const params = new URLSearchParams({ skillId: config.skillId, difficulty: config.difficulty });
      navigate(`/voice-interview?${params.toString()}`, {
        state: {
          voiceConfig: {
            skillId: config.skillId,
            difficulty: config.difficulty,
            techEnabled: true,
            projectEnabled: true,
            hrEnabled: true,
            plannedDuration: config.plannedDuration,
            resumeId: config.resumeId,
            llmProvider: config.llmProvider,
          },
        },
      });
    }
  };

  return (
    <div className="max-w-5xl mx-auto">
      <PageHeader
        eyebrow="面试准备"
        title="模拟面试"
        description="选择文字或语音模式，配置方向与难度后开始练习。"
      />

      {/* 配置区域 */}
      <div className="surface-card p-6 md:p-8 mb-6">
        <div className="space-y-6">
          {/* 面试模式 */}
          <InterviewModeSelector value={config.mode} onChange={config.setMode} />

          <InterviewSkillSelector
            skills={config.skills}
            loading={config.loadingSkills}
            value={config.skillId}
            onChange={config.setSkillId}
            isCustomSkill={config.isCustomSkill}
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
          />

          {/* 题目数量（文字面试）：提到主面板，不再藏在「更多选项」里 */}
          {config.mode === 'text' && (
            <div>
              <label className="block mb-3 text-sm font-semibold text-stone-700 dark:text-stone-200">
                题目数量
              </label>
              <div className="grid grid-cols-4 gap-2">
                {[6, 8, 10, 12].map(n => (
                  <button
                    key={n}
                    onClick={() => config.setQuestionCount(n)}
                    className={`py-2.5 rounded-xl text-sm font-medium transition-all border ${
                      config.questionCount === n
                        ? 'bg-primary-600 border-primary-600 text-white shadow-sm shadow-primary-500/25'
                        : 'bg-white/60 dark:bg-stone-900/50 border-stone-200 dark:border-stone-700 text-stone-600 dark:text-stone-300 hover:border-primary-300 dark:hover:border-primary-700'
                    }`}
                  >
                    {n} 题
                  </button>
                ))}
              </div>
              <p className="mt-2 text-xs text-stone-400 dark:text-stone-500">
                AI 面试官按大纲逐题出题，提前交卷时按实际出题数评分
              </p>
            </div>
          )}

          <InterviewAdvancedOptions
            mode={config.mode}
            showMore={config.showMore}
            onShowMoreChange={config.setShowMore}
            resumeId={config.resumeId}
            onResumeChange={config.setResumeId}
            resumes={config.resumes}
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

        {/* 开始面试按钮 */}
        <div className="mt-6 pt-6 border-t border-stone-200/80 dark:border-stone-800">
          <motion.button
            onClick={handleStart}
            whileHover={{ scale: 1.005 }}
            whileTap={{ scale: 0.995 }}
            disabled={config.isCustomStartDisabled}
            className="w-full px-6 py-3 rounded-xl font-medium text-sm btn-primary disabled:opacity-50 disabled:cursor-not-allowed"
          >
            开始{config.mode === 'text' ? '文字' : '语音'}面试
          </motion.button>
        </div>
      </div>

      <CandidateMemoryPanel skillId={config.skillId} className="mb-6" />

      {/* 最近面试记录 */}
      <div className="surface-card p-6 md:p-8">
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-base font-semibold text-stone-900 dark:text-stone-50">最近面试记录</h2>
          <Link
            to="/interviews"
            className="text-sm text-primary-600 hover:text-primary-700 dark:text-primary-400 font-medium transition-colors"
          >
            查看全部
          </Link>
        </div>

        {loadingRecent ? (
          <LoadingState
            className="flex items-center justify-center py-10"
            spinnerClassName="w-6 h-6 text-primary-500 animate-spin"
          />
        ) : recentInterviews.length === 0 ? (
          <EmptyState
            title="暂无面试记录，选择方向开始第一次面试吧"
            className="text-center py-10"
            titleClassName="text-slate-400 dark:text-slate-500 text-sm"
          />
        ) : (
          <div className="space-y-2">
            {recentInterviews.map((item, index) => {
              const isCompleted = isEvaluationCompleted(item.evaluateStatus, item.status);
              const isEvaluating = isEvaluationProcessing(item.evaluateStatus);
              return (
                <motion.div
                  key={item.id}
                  initial={{ opacity: 0, y: 10 }}
                  animate={{ opacity: 1, y: 0 }}
                  transition={{ delay: index * 0.05 }}
                  onClick={() => {
                    if (item.type === 'text') {
                      navigate(`/interviews/${item.id}`);
                    } else if (item.voiceSessionId) {
                      navigate(`/voice-interview/${item.voiceSessionId}/evaluation`);
                    }
                  }}
                  className="flex items-center gap-4 p-3.5 rounded-xl hover:bg-stone-50 dark:hover:bg-stone-900/50 transition-colors cursor-pointer group border border-transparent hover:border-stone-200/80 dark:hover:border-stone-800"
                >
                  {/* 类型图标 */}
                  <div className={`w-10 h-10 rounded-xl flex items-center justify-center flex-shrink-0 ${
                    item.type === 'text'
                      ? 'bg-blue-100 dark:bg-blue-900/30 text-blue-600 dark:text-blue-400'
                      : 'bg-emerald-100 dark:bg-emerald-900/30 text-emerald-600 dark:text-emerald-400'
                  }`}>
                    {item.type === 'text' ? <FileText className="w-5 h-5" /> : <Mic className="w-5 h-5" />}
                  </div>

                  {/* 信息 */}
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2">
                      <span className="font-medium text-sm text-slate-800 dark:text-white truncate">{item.title}</span>
                      <span className={`px-2 py-0.5 rounded text-[10px] font-medium ${
                        item.type === 'text'
                          ? 'bg-blue-50 dark:bg-blue-900/20 text-blue-600 dark:text-blue-400'
                          : 'bg-emerald-50 dark:bg-emerald-900/20 text-emerald-600 dark:text-emerald-400'
                      }`}>
                        {item.type === 'text' ? '文字' : '语音'}
                      </span>
                    </div>
                    <div className="flex items-center gap-3 mt-1">
                      <span className="text-xs text-slate-400 dark:text-slate-500">
                        {formatDateTime(item.createdAt)}
                      </span>
                      {isEvaluating && (
                        <InterviewStatusBadge
                          status={item.status}
                          evaluateStatus={item.evaluateStatus}
                          className="flex items-center gap-1"
                          iconClassName="w-3 h-3 text-blue-500 animate-spin"
                          textClassName="text-xs text-blue-500"
                        />
                      )}
                      {isCompleted && item.overallScore !== null && (
                        <span className="text-xs text-slate-600 dark:text-slate-300">
                          得分 <span className={`font-bold ${getScoreTextColor(item.overallScore)}`}>{item.overallScore}</span>
                        </span>
                      )}
                    </div>
                  </div>

                  {/* 箭头 */}
                  <ChevronRight className="w-4 h-4 text-slate-300 dark:text-slate-600 group-hover:text-primary-500 group-hover:translate-x-0.5 transition-all flex-shrink-0" />
                </motion.div>
              );
            })}
          </div>
        )}
      </div>

    </div>
  );
}
