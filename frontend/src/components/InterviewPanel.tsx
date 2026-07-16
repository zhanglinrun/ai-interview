import {useMemo, useState} from 'react';
import {CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis} from 'recharts';
import {formatDateOnly} from '../utils/date';
import {getScoreColor} from '../utils/score';
import type {InterviewItem} from '../api/history';
import {historyApi} from '../api/history';
import {getErrorMessage} from '../api/request';
import DeleteConfirmDialog from './DeleteConfirmDialog';
import LoadingButtonContent from './LoadingButtonContent';
import {EmptyState, LoadingState} from './PageState';
import {Calendar, ChevronRight, Download, MessageSquare, Mic, Trash2, TrendingUp} from 'lucide-react';

interface InterviewPanelProps {
  interviews: InterviewItem[];
  onStartInterview: () => void;
  onViewInterview: (sessionId: string) => void;
  onExportInterview: (sessionId: string) => void;
  onDeleteInterview: (sessionId: string) => void;
  exporting: string | null;
  loadingInterview: boolean;
}

/**
 * 面试记录面板组件
 */
export default function InterviewPanel({
  interviews,
  onStartInterview,
  onViewInterview,
  onExportInterview,
  onDeleteInterview,
  exporting,
  loadingInterview
}: InterviewPanelProps) {
  const [deletingSessionId, setDeletingSessionId] = useState<string | null>(null);
  const [deleteConfirm, setDeleteConfirm] = useState<{ sessionId: string } | null>(null);

  const handleDeleteClick = (sessionId: string) => {
    setDeleteConfirm({ sessionId });
  };

  const handleDeleteConfirm = async () => {
    if (!deleteConfirm) return;

    const { sessionId } = deleteConfirm;
    setDeletingSessionId(sessionId);
    try {
      await historyApi.deleteInterview(sessionId);
      onDeleteInterview(sessionId);
      setDeleteConfirm(null);
    } catch (err) {
      alert(getErrorMessage(err, '删除失败，请稍后重试'));
    } finally {
      setDeletingSessionId(null);
    }
  };

  // 准备图表数据
  const chartData = useMemo(() => {
    return interviews
      .map((interview, index) => ({ interview, index }))
      .filter(({ interview }) => interview.overallScore !== null)
      .map(({ interview, index }) => ({
        name: formatDateOnly(interview.createdAt),
        score: interview.overallScore || 0,
        index: interviews.length - index
      }))
      .reverse();
  }, [interviews]);

  if (interviews.length === 0) {
    return (
      <EmptyState
        iconNode={
          <div className="w-12 h-12 mx-auto mb-4 bg-slate-100 dark:bg-slate-700 rounded-lg flex items-center justify-center">
            <Mic className="w-8 h-8 text-slate-400" />
          </div>
        }
        title="暂无面试记录"
        description="开始一场模拟面试后，记录会显示在这里。"
        className="surface-card p-10 text-center"
        descriptionClassName="text-slate-500 dark:text-slate-400 mb-6"
        action={
        <button
          onClick={onStartInterview}
          className="btn-primary px-4 py-2 text-sm"
        >
          开始模拟面试
        </button>
        }
      />
    );
  }

  return (
    <div className="space-y-5">
      {/* 面试表现趋势图 */}
      {chartData.length > 0 && (
        <section className="surface-card p-5">
          <div className="flex items-center justify-between mb-6">
            <div className="flex items-center gap-2">
              <TrendingUp className="w-5 h-5 text-primary-500" />
              <span className="font-semibold text-slate-800 dark:text-white">面试表现趋势</span>
            </div>
            <span className="text-sm text-slate-500 dark:text-slate-400">共 {chartData.length} 场练习</span>
          </div>

          <div className="h-48">
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={chartData} margin={{ top: 10, right: 30, left: 0, bottom: 0 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="#e2e8f0" className="dark:stroke-slate-700"/>
                <XAxis
                    dataKey="name"
                  axisLine={false}
                  tickLine={false}
                  tick={{ fill: '#94a3b8', fontSize: 12 }}
                />
                <YAxis
                  domain={[0, 100]}
                  axisLine={false}
                  tickLine={false}
                  tick={{ fill: '#94a3b8', fontSize: 12 }}
                />
                <Tooltip
                    contentStyle={{
                      backgroundColor: '#fff',
                    border: '1px solid #e2e8f0',
                    borderRadius: '8px',
                    boxShadow: '0 1px 4px rgba(0,0,0,0.08)'
                  }}
                  formatter={(value) => [`${value} 分`, '得分']}
                />
                <Line
                    type="monotone"
                    dataKey="score"
                    stroke="#0d9488"
                  strokeWidth={3}
                  dot={{ fill: '#0d9488', strokeWidth: 2, r: 4 }}
                  activeDot={{ r: 6, fill: '#0d9488' }}
                />
              </LineChart>
            </ResponsiveContainer>
          </div>
        </section>
      )}

      {/* 历史面试场次 */}
      <section className="surface-card p-5">
        <div className="flex items-center justify-between mb-6">
          <span className="font-semibold text-slate-800 dark:text-white">历史面试场次</span>
        </div>

        <div className="space-y-4">
          {interviews.map(interview => (
            <InterviewItemCard
              key={interview.id}
              interview={interview}
              exporting={exporting === interview.sessionId}
              deleting={deletingSessionId === interview.sessionId}
              onView={() => onViewInterview(interview.sessionId)}
              onExport={() => onExportInterview(interview.sessionId)}
              onDelete={() => handleDeleteClick(interview.sessionId)}
            />
          ))}
        </div>

        {/* 删除确认对话框 */}
        <DeleteConfirmDialog
          open={deleteConfirm !== null}
          item={deleteConfirm ? { sessionId: deleteConfirm.sessionId } : null}
          itemType="面试记录"
          loading={deletingSessionId !== null}
          onConfirm={handleDeleteConfirm}
          onCancel={() => setDeleteConfirm(null)}
          customMessage="确定要删除这条面试记录吗？删除后无法恢复。"
        />

        {loadingInterview && (
            <div className="fixed inset-0 bg-black/20 dark:bg-black/50 flex items-center justify-center z-50">
              <LoadingState
                label="加载面试详情..."
                className="surface-card p-5 flex items-center gap-4"
                spinnerClassName="w-8 h-8 text-primary-500 animate-spin"
                textClassName="text-slate-600 dark:text-slate-300"
              />
          </div>
        )}
      </section>
    </div>
  );
}

// 面试项卡片组件
function InterviewItemCard({
  interview,
  exporting,
  deleting,
  onView,
  onExport,
  onDelete
}: {
  interview: InterviewItem;
  exporting: boolean;
  deleting: boolean;
  onView: () => void;
  onExport: () => void;
  onDelete: () => void;
}) {
  return (
    <article className="flex items-center gap-2 rounded-lg bg-stone-50 p-3 dark:bg-stone-800/60 sm:gap-3 sm:p-4">
      <button
        type="button"
        onClick={onView}
        className="flex min-w-0 flex-1 items-center gap-3 text-left"
        aria-label={`查看模拟面试 ${formatDateOnly(interview.createdAt)}`}
      >
        <span className={`flex h-12 w-12 flex-shrink-0 items-center justify-center rounded-full text-base font-bold ${
          interview.overallScore !== null
            ? getScoreColor(interview.overallScore, [85, 70])
            : 'bg-stone-100 text-stone-400 dark:bg-stone-700'
        }`}>
          {interview.overallScore ?? '-'}
        </span>

        <span className="min-w-0 flex-1">
          <span className="block truncate font-medium text-stone-800 dark:text-white">
            模拟面试
          </span>
          <span className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-stone-500 dark:text-stone-400 sm:text-sm">
            <span className="flex items-center gap-1">
              <Calendar className="h-4 w-4" />
              {formatDateOnly(interview.createdAt)}
            </span>
            <span className="flex items-center gap-1">
              <MessageSquare className="h-4 w-4" />
              {interview.totalQuestions} 题
            </span>
          </span>
        </span>

        <ChevronRight className="h-5 w-5 flex-shrink-0 text-stone-400" />
      </button>

      <div className="flex items-center gap-1 border-l border-stone-200 pl-2 dark:border-stone-700">
      <button
        type="button"
        onClick={onExport}
        disabled={exporting}
        className="rounded-lg p-2 text-stone-400 transition-colors hover:bg-white hover:text-primary-600 disabled:cursor-not-allowed disabled:opacity-50 dark:hover:bg-stone-700"
        title="导出面试记录"
        aria-label="导出面试记录"
      >
        <Download className="h-5 w-5" />
      </button>

        {/* 删除按钮 */}
        <button
          type="button"
          onClick={onDelete}
          disabled={deleting}
          className="p-2 text-slate-400 hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-900/30 rounded-lg transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
          title="删除面试记录"
          aria-label="删除面试记录"
        >
          <LoadingButtonContent
            loading={deleting}
            loadingText="删除中"
            spinnerClassName="w-5 h-5 animate-spin text-red-500"
            iconOnly
          >
            <Trash2 className="w-5 h-5" />
          </LoadingButtonContent>
        </button>
      </div>
    </article>
  );
}
