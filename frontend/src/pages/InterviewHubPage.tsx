import { useState, useEffect, useCallback } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { motion } from 'framer-motion';
import {
  ChevronRight,
  FileText,
  Mic,
  Sparkles,
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
import InterviewAdvancedOptions from '../components/InterviewAdvancedOptions';
import InterviewDifficultySelector from '../components/InterviewDifficultySelector';
import InterviewModeSelector from '../components/InterviewModeSelector';
import InterviewSkillSelector from '../components/InterviewSkillSelector';
import InterviewStatusBadge from '../components/InterviewStatusBadge';
import { EmptyState, LoadingState } from '../components/PageState';
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
      {/* 页面标题 */}
      <div className="mb-8">
        <h1 className="text-2xl font-bold text-slate-800 dark:text-white flex items-center gap-3">
          <Sparkles className="w-7 h-7 text-primary-500" />
          模拟面试
        </h1>
        <p className="text-slate-500 dark:text-slate-400 mt-1">选择面试模式和方向，快速开始练习</p>
      </div>

      {/* 配置区域 */}
      <div className="bg-white dark:bg-slate-800 rounded-2xl shadow-sm border border-slate-100 dark:border-slate-700 p-6 mb-8">
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
          />
        </div>

        {/* 开始面试按钮 */}
        <div className="mt-6 pt-6 border-t border-slate-100 dark:border-slate-700">
          <motion.button
            onClick={handleStart}
            whileHover={{ scale: 1.01 }}
            whileTap={{ scale: 0.99 }}
            disabled={config.isCustomStartDisabled}
            className="w-full px-6 py-3 rounded-xl font-semibold text-sm transition-all
              bg-gradient-to-r from-primary-500 to-primary-600 hover:from-primary-600 hover:to-primary-700
              text-white shadow-lg shadow-primary-500/25 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            开始{config.mode === 'text' ? '文字' : '语音'}面试
          </motion.button>
        </div>
      </div>

      {/* 最近面试记录 */}
      <div className="bg-white dark:bg-slate-800 rounded-2xl shadow-sm border border-slate-100 dark:border-slate-700 p-6">
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-lg font-bold text-slate-800 dark:text-white">最近面试记录</h2>
          <Link
            to="/interviews"
            className="text-sm text-primary-500 hover:text-primary-600 font-medium transition-colors"
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
                  className="flex items-center gap-4 p-4 rounded-xl hover:bg-slate-50 dark:hover:bg-slate-700/50 transition-colors cursor-pointer group"
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
