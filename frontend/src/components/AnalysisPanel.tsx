import {useMemo} from 'react';
import RadarChart from './RadarChart';
import ScoreProgressBar from './ScoreProgressBar';
import LoadingButtonContent from './LoadingButtonContent';
import {EmptyState} from './PageState';
import {formatDateTime} from '../utils/date';
import {
  isAnalyzeStatusFailed,
  isAnalyzeStatusRunning,
  shouldPollAnalyzeResult,
} from '../utils/analyzeStatus';
import {AlertCircle, CheckCircle2, Clock, Download, Loader2, RefreshCw, Target, TrendingUp,} from 'lucide-react';
import type {AnalysisItem, AnalyzeStatus} from '../api/history';
import type {Suggestion} from '../types/resume';

type SuggestionPriority = Suggestion['priority'];

type PriorityStyle = {
  cardClassName: string;
  badgeClassName: string;
  headerBgClassName: string;
  headerTextClassName: string;
  dividerClassName: string;
};

type SuggestionsByPriority = {
  high: Suggestion[];
  medium: Suggestion[];
  low: Suggestion[];
};

const EMPTY_SUGGESTIONS_BY_PRIORITY: SuggestionsByPriority = {
  high: [],
  medium: [],
  low: [],
};

const PRIORITY_STYLES: Record<SuggestionPriority, PriorityStyle> = {
  '高': {
    cardClassName: 'bg-red-50 dark:bg-red-900/20 border-red-200 dark:border-red-800 text-red-700 dark:text-red-400',
    badgeClassName: 'bg-red-500 text-white',
    headerBgClassName: 'bg-red-100 dark:bg-red-900/50',
    headerTextClassName: 'text-red-700 dark:text-red-300',
    dividerClassName: 'bg-red-100 dark:bg-red-900/50',
  },
  '中': {
    cardClassName: 'bg-amber-50 dark:bg-amber-900/20 border-amber-200 dark:border-amber-800 text-amber-700 dark:text-amber-400',
    badgeClassName: 'bg-amber-500 text-white',
    headerBgClassName: 'bg-amber-100 dark:bg-amber-900/50',
    headerTextClassName: 'text-amber-700 dark:text-amber-300',
    dividerClassName: 'bg-amber-100 dark:bg-amber-900/50',
  },
  '低': {
    cardClassName: 'bg-blue-50 dark:bg-blue-900/20 border-blue-200 dark:border-blue-800 text-blue-700 dark:text-blue-400',
    badgeClassName: 'bg-blue-500 text-white',
    headerBgClassName: 'bg-blue-100 dark:bg-blue-900/50',
    headerTextClassName: 'text-blue-700 dark:text-blue-300',
    dividerClassName: 'bg-blue-100 dark:bg-blue-900/50',
  },
};

const CATEGORY_BADGE_CLASS: Record<string, string> = {
  '项目': 'bg-purple-100 dark:bg-purple-900/50 text-purple-700 dark:text-purple-300',
  '技能': 'bg-indigo-100 dark:bg-indigo-900/50 text-indigo-700 dark:text-indigo-300',
  '内容': 'bg-emerald-100 dark:bg-emerald-900/50 text-emerald-700 dark:text-emerald-300',
  '格式': 'bg-pink-100 dark:bg-pink-900/50 text-pink-700 dark:text-pink-300',
  '结构': 'bg-cyan-100 dark:bg-cyan-900/50 text-cyan-700 dark:text-cyan-300',
  '表达': 'bg-orange-100 dark:bg-orange-900/50 text-orange-700 dark:text-orange-300',
};

const DEFAULT_CATEGORY_BADGE_CLASS =
  'bg-slate-100 dark:bg-slate-700 text-slate-700 dark:text-slate-300';

interface AnalysisPanelProps {
  analysis: AnalysisItem | null | undefined;
  analyzeStatus?: AnalyzeStatus;
  analyzeError?: string;
  onExport: () => void;
  exporting: boolean;
  onReanalyze?: () => void;
  reanalyzing?: boolean;
}

/**
 * 简历分析面板组件
 */
export default function AnalysisPanel({
  analysis,
  analyzeStatus,
  analyzeError,
  onExport,
  exporting,
  onReanalyze,
  reanalyzing,
}: AnalysisPanelProps) {
  // 准备雷达图数据
  const radarData = useMemo(() => {
    if (!analysis) return [];

    const projectScore = analysis.projectScore || 0;
    const skillMatchScore = analysis.skillMatchScore || 0;
    const contentScore = analysis.contentScore || 0;
    const structureScore = analysis.structureScore || 0;
    const expressionScore = analysis.expressionScore || 0;

    const projectFullMark = 40;
    const skillMatchFullMark = 20;
    const contentFullMark = 15;
    const structureFullMark = 15;
    const expressionFullMark = 10;

    return [
      {
        subject: '表达专业性',
        score: expressionScore,
        fullMark: expressionFullMark
      },
      {
        subject: '技能匹配',
        score: skillMatchScore,
        fullMark: skillMatchFullMark
      },
      {
        subject: '内容完整性',
        score: contentScore,
        fullMark: contentFullMark
      },
      {
        subject: '结构清晰度',
        score: structureScore,
        fullMark: structureFullMark
      },
      {
        subject: '项目经验',
        score: projectScore,
        fullMark: projectFullMark
      }
    ];
  }, [analysis]);

  // 按优先级分类建议
  const suggestionsByPriority = useMemo(() => {
    if (!analysis?.suggestions) return EMPTY_SUGGESTIONS_BY_PRIORITY;

    return {
      high: analysis.suggestions.filter((s) => s.priority === '高'),
      medium: analysis.suggestions.filter((s) => s.priority === '中'),
      low: analysis.suggestions.filter((s) => s.priority === '低')
    };
  }, [analysis]);

  // 判断是否为"分析中"状态
  const isProcessing = shouldPollAnalyzeResult(analyzeStatus, Boolean(analysis));

  // 处理分析中状态
  if (isProcessing) {
    const isExplicitProcessing = isAnalyzeStatusRunning(analyzeStatus);
    return (
      <EmptyState
        iconNode={
          <div className="w-12 h-12 mx-auto mb-4 bg-blue-100 dark:bg-blue-900/50 rounded-lg flex items-center justify-center">
            {isExplicitProcessing ? (
              <Loader2 className="w-8 h-8 text-blue-500 dark:text-blue-400 animate-spin" />
            ) : (
              <Clock className="w-8 h-8 text-yellow-500 dark:text-yellow-400" />
            )}
          </div>
        }
        title={isExplicitProcessing ? '正在分析简历' : '等待分析'}
        description={isExplicitProcessing
          ? '分析完成后，页面会自动显示结果。'
          : '简历已上传，正在等待处理。'}
        className="surface-card p-10 text-center"
        descriptionClassName="text-slate-500 dark:text-slate-400 mb-4"
        action={<p className="text-sm text-slate-400 dark:text-slate-500">页面将自动刷新显示分析结果</p>}
      />
    );
  }

  // 处理分析失败状态
  if (isAnalyzeStatusFailed(analyzeStatus) || !analysis) {
    return (
      <EmptyState
        iconNode={
          <div className="w-12 h-12 mx-auto mb-4 bg-red-100 dark:bg-red-900/50 rounded-lg flex items-center justify-center">
            <AlertCircle className="w-8 h-8 text-red-500 dark:text-red-400" />
          </div>
        }
        title="分析失败"
        description="暂时无法完成分析，请稍后重试。"
        className="surface-card p-10 text-center"
        descriptionClassName="text-slate-500 dark:text-slate-400 mb-4"
        action={
          <>
            {analyzeError && (
              <div className="mt-4 p-4 bg-red-50 dark:bg-red-900/30 border border-red-200 dark:border-red-800 rounded-lg text-left mb-4">
                <p className="text-sm text-red-600 dark:text-red-400">{analyzeError}</p>
              </div>
            )}
            {onReanalyze && (
              <button
                onClick={onReanalyze}
                disabled={reanalyzing}
                className="btn-primary px-4 py-2 text-sm disabled:opacity-50 flex items-center gap-2 mx-auto"
              >
                <LoadingButtonContent
                  loading={Boolean(reanalyzing)}
                  loadingText="重新分析中..."
                  className="inline-flex items-center gap-2"
                >
                  <span className="inline-flex items-center gap-2">
                    <RefreshCw className="w-4 h-4" />
                    重新分析
                  </span>
                </LoadingButtonContent>
              </button>
            )}
          </>
        }
      />
    );
  }

  const projectScore = analysis.projectScore || 0;
  const skillMatchScore = analysis.skillMatchScore || 0;
  const contentScore = analysis.contentScore || 0;
  const structureScore = analysis.structureScore || 0;
  const expressionScore = analysis.expressionScore || 0;

  return (
    <div className="space-y-5">
      {/* 核心评价和雷达图 */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-5">
        {/* 核心评价 */}
        <div className="surface-card p-5">
          <div className="flex items-center justify-between mb-6">
            <div className="flex items-center gap-2 text-slate-500 dark:text-slate-400">
              <TrendingUp className="w-5 h-5" />
              <span className="font-semibold">核心评价</span>
            </div>
            <button
              onClick={onExport}
              disabled={exporting}
              className="btn-secondary flex items-center gap-2 px-4 py-2 text-sm disabled:opacity-50"
            >
              <LoadingButtonContent
                loading={exporting}
                loadingText="导出中..."
              >
                <span className="inline-flex items-center gap-2">
                  <Download className="w-4 h-4" />
                  导出分析报告
                </span>
              </LoadingButtonContent>
            </button>
          </div>

          <div
              className="rounded-lg border border-stone-200 bg-stone-50 p-5 dark:border-stone-700 dark:bg-stone-900/40">
            <p className="text-lg text-slate-800 dark:text-white leading-relaxed mb-6">
              {analysis.summary || '暂无总结'}
            </p>

            <div className="grid grid-cols-2 gap-4 mb-4">
              <div className="rounded-lg border border-stone-200 bg-white p-4 dark:border-stone-700 dark:bg-slate-800">
                <span className="text-sm font-semibold text-emerald-600 dark:text-emerald-400 block mb-2">总分</span>
                <span className="text-4xl font-bold text-slate-900 dark:text-white">{analysis.overallScore || 0}</span>
                <span className="text-sm text-slate-500 dark:text-slate-400">/ 100</span>
              </div>
              <div className="rounded-lg border border-stone-200 bg-white p-4 dark:border-stone-700 dark:bg-slate-800">
                <span
                    className="text-sm font-semibold text-emerald-600 dark:text-emerald-400 block mb-2">分析时间</span>
                <span className="text-sm text-slate-700 dark:text-slate-300">
                  {formatDateTime(analysis.analyzedAt)}
                </span>
              </div>
            </div>

            {/* 优势标签 */}
            {analysis.strengths && analysis.strengths.length > 0 && (
                <div className="rounded-lg border border-stone-200 bg-white p-4 dark:border-stone-700 dark:bg-slate-800">
                  <span
                      className="text-sm font-semibold text-emerald-600 dark:text-emerald-400 block mb-3">优势亮点</span>
                <div className="flex flex-wrap gap-2">
                  {analysis.strengths.map((s: string, i: number) => (
                      <span key={i}
                            className="px-3 py-1.5 bg-emerald-100 dark:bg-emerald-900/50 text-emerald-700 dark:text-emerald-300 border border-emerald-200 dark:border-emerald-800 rounded-md text-sm font-medium">
                      {s}
                    </span>
                  ))}
                </div>
              </div>
            )}
          </div>
        </div>

        {/* 多维度评分雷达图 */}
        <div className="surface-card p-5">
          <div className="flex items-center gap-2 text-slate-500 dark:text-slate-400 mb-6">
            <Target className="w-5 h-5" />
            <span className="font-semibold">多维度评分</span>
          </div>

          <RadarChart data={radarData} height={320} />

          {/* 维度得分详情 */}
          <div className="mt-4 grid grid-cols-2 gap-3">
            <ScoreProgressBar
              label="项目经验"
              score={projectScore}
              maxScore={40}
              color="bg-purple-500"
              className="col-span-2"
            />
            <ScoreProgressBar
              label="技能匹配"
              score={skillMatchScore}
              maxScore={20}
              color="bg-blue-500"
            />
            <ScoreProgressBar
              label="内容完整性"
              score={contentScore}
              maxScore={15}
              color="bg-emerald-500"
            />
            <ScoreProgressBar
              label="结构清晰度"
              score={structureScore}
              maxScore={15}
              color="bg-cyan-500"
            />
            <ScoreProgressBar
              label="表达专业性"
              score={expressionScore}
              maxScore={10}
              color="bg-orange-500"
            />
          </div>
        </div>
      </div>

      {/* 改进建议 - 按优先级分类 */}
      <div className="surface-card p-5">
        <div className="flex items-center gap-2 text-slate-500 dark:text-slate-400 mb-6">
          <CheckCircle2 className="w-5 h-5" />
          <span className="font-semibold">改进建议</span>
          <span className="text-sm text-slate-400 dark:text-slate-500">
            ({analysis.suggestions?.length || 0} 条)
          </span>
        </div>

        <div className="space-y-6">
          {/* 高优先级 */}
          {suggestionsByPriority.high.length > 0 && (
            <SuggestionSection
              priority="高"
              suggestions={suggestionsByPriority.high}
            />
          )}

          {/* 中优先级 */}
          {suggestionsByPriority.medium.length > 0 && (
            <SuggestionSection
              priority="中"
              suggestions={suggestionsByPriority.medium}
            />
          )}

          {/* 低优先级 */}
          {suggestionsByPriority.low.length > 0 && (
            <SuggestionSection
              priority="低"
              suggestions={suggestionsByPriority.low}
            />
          )}

          {analysis.suggestions?.length === 0 && (
            <EmptyState
              title="暂无改进建议"
              className="text-center py-8"
              titleClassName="text-slate-500 dark:text-slate-400"
            />
          )}
        </div>
      </div>
    </div>
  );
}

// 建议分组组件
function SuggestionSection({
  priority,
  suggestions
}: {
  priority: SuggestionPriority;
  suggestions: Suggestion[];
}) {
  const styles = PRIORITY_STYLES[priority];

  return (
    <div>
      <div className="flex items-center gap-2 mb-4">
        <span className={`px-3 py-1 ${styles.headerBgClassName} ${styles.headerTextClassName} rounded-md text-sm font-semibold`}>
          {priority}优先级 ({suggestions.length})
        </span>
        <div className={`flex-1 h-px ${styles.dividerClassName}`}></div>
      </div>
      <div className="space-y-3">
        {suggestions.map((s, i) => (
          <div
            key={`${priority}-${i}`}
            className={`p-4 rounded-lg border ${styles.cardClassName}`}
          >
            <div className="flex items-start gap-3 mb-2">
              <span className={`px-2 py-0.5 rounded text-xs font-semibold ${styles.badgeClassName}`}>
                {priority}
              </span>
              <span className={`px-2 py-0.5 rounded text-xs font-medium ${CATEGORY_BADGE_CLASS[s.category || '其他'] || DEFAULT_CATEGORY_BADGE_CLASS}`}>
                {s.category || '其他'}
              </span>
            </div>
            <div className="mb-2">
              <p className="font-semibold text-slate-900 dark:text-white mb-1">{s.issue || '问题描述'}</p>
              <p className="text-sm leading-relaxed text-slate-700 dark:text-slate-300">{s.recommendation || '暂无具体建议'}</p>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
