import {useCallback, useEffect, useState} from 'react';
import {historyApi, type EvaluateStatus} from '../api/history';
import {getErrorMessage} from '../api/request';
import {interviewApi, type TextSessionMeta} from '../api/interview';
import {jobTargetApi} from '../api/jobTarget';
import {compareDateDesc, formatDate} from '../utils/date';
import {downloadBlob} from '../utils/download';
import {formatShortId} from '../utils/format';
import {
  isCompletedInterviewStatus,
  isEvaluationCompleted,
  isEvaluationFailed,
  isEvaluationProcessing,
} from '../utils/interviewStatus';
import DeleteConfirmDialog from '../components/DeleteConfirmDialog';
import LoadingButtonContent from '../components/LoadingButtonContent';
import {EmptyState, LoadingState} from '../components/PageState';
import InterviewStatusBadge from '../components/InterviewStatusBadge';
import InterviewTypeBadge from '../components/InterviewTypeBadge';
import SearchInput from '../components/SearchInput';
import ScoreProgress from '../components/ScoreProgress';
import StatCard from '../components/StatCard';
import PageHeader from '../components/ui/PageHeader';
import {FAST_POLLING_INTERVAL_MS, useConditionalPolling} from '../hooks/useConditionalPolling';
import {
  CheckCircle,
  Download,
  FileText,
  PlayCircle,
  RotateCcw,
  Trash2,
  TrendingUp,
  Users,
} from 'lucide-react';

interface UnifiedInterviewItem {
  id: string;
  title: string;
  sessionId: string;
  status: string;
  evaluateStatus?: EvaluateStatus;
  evaluateError?: string;
  overallScore: number | null;
  totalQuestions?: number;
  createdAt: string;
  resumeId?: number;
  jobInterview: boolean;
  currentStage?: string;
}

interface InterviewStats {
  totalCount: number;
  completedCount: number;
  averageScore: number | null;
}

const JOB_INTERVIEW_STAGE_LABELS: Record<string, string> = {
  PROJECT_DEEP_DIVE: '项目深挖',
  POSITION_TECH: '岗位技术',
  ALGORITHM: '算法题',
  ENGINEERING_SCENARIO: '工程场景',
};

function hasEvaluationCompleted(item: UnifiedInterviewItem): boolean {
  return isEvaluationCompleted(item.evaluateStatus, item.status);
}

function hasEvaluationProcessing(item: UnifiedInterviewItem): boolean {
  return isEvaluationProcessing(item.evaluateStatus);
}

function canContinueInterview(item: UnifiedInterviewItem): boolean {
  return !['COMPLETED', 'EVALUATED', 'COMPLETING', 'ABORTED', 'FAILED'].includes(item.status)
    && !hasEvaluationCompleted(item);
}

function getJobInterviewStageLabel(stage: string): string {
  return JOB_INTERVIEW_STAGE_LABELS[stage] ?? '其他阶段';
}

function calculateStats(items: UnifiedInterviewItem[]): InterviewStats {
  const evaluated = items.filter(i => (
    !i.jobInterview && hasEvaluationCompleted(i) && i.overallScore !== null
  ));
  const completed = items.filter(i => isCompletedInterviewStatus(i.status) || hasEvaluationCompleted(i));
  const totalScore = evaluated.reduce((sum, i) => sum + (i.overallScore || 0), 0);

  return {
    totalCount: items.length,
    completedCount: completed.length,
    averageScore: evaluated.length > 0 ? Math.round(totalScore / evaluated.length) : null,
  };
}

function statsEqual(a: InterviewStats | null, b: InterviewStats): boolean {
  if (!a) return false;

  return a.totalCount === b.totalCount
    && a.completedCount === b.completedCount
    && a.averageScore === b.averageScore;
}

interface InterviewHistoryPageProps {
  onBack: () => void;
  onViewInterview: (sessionId: string, resumeId?: number, jobInterview?: boolean, status?: string) => void;
  onRestartInterview?: (resumeId: number) => void;
  onContinueInterview?: (sessionId: string, jobInterview?: boolean) => void;
}

/** Shallow comparison for polling change-detection */
function itemsEqual(a: UnifiedInterviewItem[], b: UnifiedInterviewItem[]): boolean {
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) {
    const ai = a[i], bi = b[i];
    if (ai.id !== bi.id || ai.status !== bi.status ||
        ai.evaluateStatus !== bi.evaluateStatus || ai.overallScore !== bi.overallScore) return false;
  }
  return true;
}

export default function InterviewHistoryPage({ onBack: _onBack, onViewInterview, onRestartInterview, onContinueInterview }: InterviewHistoryPageProps) {
  const [items, setItems] = useState<UnifiedInterviewItem[]>([]);
  const [stats, setStats] = useState<InterviewStats | null>(null);
  const [loading, setLoading] = useState(true);
  const [searchTerm, setSearchTerm] = useState('');
  const [deletingSessionId, setDeletingSessionId] = useState<string | null>(null);
  const [deleteItem, setDeleteItem] = useState<UnifiedInterviewItem | null>(null);
  const [exporting, setExporting] = useState<string | null>(null);

  const loadAll = useCallback(async (isPolling = false) => {
    if (!isPolling) setLoading(true);

    try {
      const all = await loadTextInterviews();
      all.sort((a, b) => compareDateDesc(a.createdAt, b.createdAt));

      setItems(prev => {
        if (isPolling && itemsEqual(prev, all)) return prev;
        return all;
      });

      const newStats = calculateStats(all);
      setStats(prev => {
        if (isPolling && statsEqual(prev, newStats)) return prev;
        return newStats;
      });
    } catch (err) {
      console.error('加载面试记录失败', err);
    } finally {
      if (!isPolling) setLoading(false);
    }
  }, []);

  // Load text interviews from dedicated API
  async function loadTextInterviews(): Promise<UnifiedInterviewItem[]> {
    try {
      const [sessions, targets] = await Promise.all([
        interviewApi.listSessions(),
        jobTargetApi.list().catch(() => []),
      ]);
      const targetTitles = new Map(targets.map((target) => [
        target.id,
        [target.company?.trim(), target.title.trim()].filter(Boolean).join(' · '),
      ]));
      return sessions.map((session: TextSessionMeta) => ({
        id: session.sessionId,
        title: session.jobInterview
          ? targetTitles.get(session.jobDescriptionId ?? -1) || '岗位实战'
          : '文字面试',
        sessionId: session.sessionId,
        status: session.status,
        evaluateStatus: session.evaluateStatus ?? undefined,
        evaluateError: session.evaluateError ?? undefined,
        overallScore: session.overallScore,
        totalQuestions: session.totalQuestions,
        createdAt: session.createdAt,
        resumeId: session.resumeId ?? undefined,
        jobInterview: session.jobInterview,
        currentStage: session.currentStage ?? undefined,
      }));
    } catch {
      return [];
    }
  }

  useEffect(() => {
    loadAll();
  }, [loadAll]);

  const hasEvaluating = items.some(i => hasEvaluationProcessing(i));
  useConditionalPolling(hasEvaluating, () => loadAll(true), FAST_POLLING_INTERVAL_MS);

  const handleRowClick = (item: UnifiedInterviewItem) => {
    onViewInterview(item.sessionId, item.resumeId, item.jobInterview, item.status);
  };

  const handleDeleteClick = (item: UnifiedInterviewItem, e: React.MouseEvent) => {
    e.stopPropagation();
    setDeleteItem(item);
  };

  const handleDeleteConfirm = async () => {
    if (!deleteItem) return;
    setDeletingSessionId(deleteItem.sessionId);
    try {
      await historyApi.deleteInterview(deleteItem.sessionId);
      await loadAll();
      setDeleteItem(null);
    } catch (err) {
      alert(getErrorMessage(err, '删除失败，请稍后重试'));
    } finally {
      setDeletingSessionId(null);
    }
  };

  const handleExport = async (sessionId: string, e: React.MouseEvent) => {
    e.stopPropagation();
    setExporting(sessionId);
    try {
      const blob = await historyApi.exportInterviewPdf(sessionId);
      downloadBlob(blob, `面试报告_${formatShortId(sessionId)}.pdf`);
    } catch (err) {
      alert(getErrorMessage(err, '导出失败，请重试'));
    } finally {
      setExporting(null);
    }
  };

  // Filter + search
  const filtered = items.filter(item => {
    if (searchTerm && !item.title.toLowerCase().includes(searchTerm.toLowerCase())) return false;
    return true;
  });

  return (
    <div className="mx-auto w-full max-w-7xl">
      <PageHeader
        title="面试记录"
        description="查看已完成的报告，或继续尚未结束的面试。"
        action={<SearchInput value={searchTerm} onChange={setSearchTerm} placeholder="搜索面试" />}
      />

      {/* Stats */}
      {stats && (
        <div className="mb-5 grid grid-cols-1 gap-3 md:grid-cols-3">
          <StatCard icon={Users} label="面试总数" value={stats.totalCount} color="bg-primary-500" />
          <StatCard icon={CheckCircle} label="已完成" value={stats.completedCount} color="bg-emerald-500" />
          <StatCard
            icon={TrendingUp}
            label="平均分数"
            value={stats.averageScore ?? '暂无可计算分数'}
            suffix={stats.averageScore === null ? undefined : '分'}
            color="bg-indigo-500"
          />
        </div>
      )}

      {/* Loading */}
      {loading && (
        <LoadingState />
      )}

      {/* Empty */}
      {!loading && filtered.length === 0 && (
        <EmptyState
          icon={Users}
          title="暂无面试记录"
          description="开始一次模拟面试后，记录将显示在这里"
        />
      )}

      {/* Table */}
      {!loading && filtered.length > 0 && (
        <div className="surface-card overflow-x-auto">
          <table className="w-full">
            <thead className="bg-slate-50 dark:bg-slate-700/50 border-b border-slate-100 dark:border-slate-600">
              <tr>
                <th className="text-left px-6 py-4 text-sm font-medium text-slate-600 dark:text-slate-300">类型</th>
                <th className="text-left px-6 py-4 text-sm font-medium text-slate-600 dark:text-slate-300">名称</th>
                <th className="text-left px-6 py-4 text-sm font-medium text-slate-600 dark:text-slate-300">状态</th>
                <th className="text-left px-6 py-4 text-sm font-medium text-slate-600 dark:text-slate-300">得分</th>
                <th className="text-left px-6 py-4 text-sm font-medium text-slate-600 dark:text-slate-300">详情</th>
                <th className="text-left px-6 py-4 text-sm font-medium text-slate-600 dark:text-slate-300">时间</th>
                <th className="text-right px-6 py-4 text-sm font-medium text-slate-600 dark:text-slate-300">操作</th>
              </tr>
            </thead>
            <tbody>
                {filtered.map((item) => (
                  <tr
                    key={item.id}
                    className="border-b border-slate-50 transition-colors hover:bg-slate-50 dark:border-slate-700 dark:hover:bg-slate-700/50"
                  >
                    <td className="px-6 py-4">
                      <InterviewTypeBadge jobInterview={item.jobInterview} />
                    </td>
                    <td className="px-6 py-4">
                      <button
                        type="button"
                        onClick={() => handleRowClick(item)}
                        className="flex items-center gap-3 rounded-lg text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary-500"
                      >
                        <FileText className="w-5 h-5 text-slate-400" />
                        <span className="font-medium text-slate-800 dark:text-white">{item.title}</span>
                      </button>
                    </td>
                    <td className="px-6 py-4">
                      <InterviewStatusBadge
                        status={item.status}
                        evaluateStatus={item.evaluateStatus}
                      />
                    </td>
                    <td className="px-6 py-4">
                      {item.jobInterview ? (
                        <span className="text-primary-600 dark:text-primary-400 text-sm">不设总分</span>
                      ) : hasEvaluationCompleted(item) && item.overallScore !== null ? (
                        <ScoreProgress score={item.overallScore} />
                      ) : hasEvaluationProcessing(item) ? (
                        <span className="text-blue-500 dark:text-blue-400 text-sm">生成中...</span>
                      ) : isEvaluationFailed(item.evaluateStatus) ? (
                        <span className="text-red-500 dark:text-red-400 text-sm" title={item.evaluateError}>失败</span>
                      ) : (
                        <span className="text-slate-400 dark:text-slate-500">-</span>
                      )}
                    </td>
                    <td className="px-6 py-4">
                      {item.jobInterview && item.currentStage ? (
                        <span className="inline-flex items-center gap-1.5 px-2.5 py-1 bg-primary-50 dark:bg-primary-950/30 text-primary-700 dark:text-primary-300 rounded-lg text-xs">
                          {getJobInterviewStageLabel(item.currentStage)}
                        </span>
                      ) : item.totalQuestions != null ? (
                        <span className="inline-flex items-center gap-1.5 px-2.5 py-1 bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300 rounded-lg text-sm">
                          {item.totalQuestions} 题
                        </span>
                      ) : (
                        <span className="text-slate-400">-</span>
                      )}
                    </td>
                    <td className="px-6 py-4 text-sm text-slate-500 dark:text-slate-400">
                      {formatDate(item.createdAt)}
                    </td>
                    <td className="px-6 py-4 text-right">
                      <div className="flex items-center justify-end gap-1">
                        {canContinueInterview(item) && onContinueInterview && (
                          <button
                            onClick={(e) => { e.stopPropagation(); onContinueInterview(item.sessionId, item.jobInterview); }}
                            aria-label={`继续面试 ${item.title}`}
                            className="p-2 text-slate-400 hover:text-blue-500 hover:bg-blue-50 dark:hover:bg-blue-900/30 rounded-lg transition-colors"
                            title="继续面试"
                          >
                            <PlayCircle className="w-4 h-4" />
                          </button>
                        )}
                        {!item.jobInterview && hasEvaluationCompleted(item) && (
                          <button
                            onClick={(e) => handleExport(item.sessionId, e)}
                            disabled={exporting === item.sessionId}
                            aria-label={`导出面试报告 ${item.title}`}
                            className="p-2 text-slate-400 hover:text-primary-500 hover:bg-primary-50 dark:hover:bg-primary-900/30 rounded-lg transition-colors disabled:opacity-50"
                            title="导出PDF"
                          >
                            <LoadingButtonContent
                              loading={exporting === item.sessionId}
                              loadingText="导出中"
                              iconOnly
                            >
                              <Download className="w-4 h-4" />
                            </LoadingButtonContent>
                          </button>
                        )}
                        {hasEvaluationCompleted(item) && item.resumeId !== undefined && onRestartInterview && (
                          <button
                            onClick={(e) => {
                              e.stopPropagation();
                              if (item.resumeId !== undefined) {
                                onRestartInterview(item.resumeId);
                              }
                            }}
                            aria-label={`重新面试 ${item.title}`}
                            className="p-2 text-slate-400 hover:text-emerald-500 hover:bg-emerald-50 dark:hover:bg-emerald-900/30 rounded-lg transition-colors"
                            title="重新面试"
                          >
                            <RotateCcw className="w-4 h-4" />
                          </button>
                        )}
                        <button
                            onClick={(e) => handleDeleteClick(item, e)}
                            disabled={deletingSessionId === item.sessionId}
                            aria-label={`删除面试记录 ${item.title}`}
                            className="p-2 text-slate-400 hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-900/30 rounded-lg transition-colors disabled:opacity-50"
                            title="删除"
                          >
                            <Trash2 className="w-4 h-4" />
                          </button>
                      </div>
                    </td>
                  </tr>
                ))}
            </tbody>
          </table>
        </div>
      )}

      <DeleteConfirmDialog
        open={deleteItem !== null}
        item={deleteItem ? { title: deleteItem.title } : null}
        itemType="面试记录"
        loading={deletingSessionId !== null}
        onConfirm={handleDeleteConfirm}
        onCancel={() => setDeleteItem(null)}
      />
    </div>
  );
}
