import {useCallback, useEffect, useState} from 'react';
import {AnimatePresence, motion} from 'framer-motion';
import {historyApi, ResumeListItem, ResumeStats} from '../api/history';
import {getErrorMessage} from '../api/request';
import AnalyzeStatusBadge from './AnalyzeStatusBadge';
import DeleteConfirmDialog from './DeleteConfirmDialog';
import LoadingButtonContent from './LoadingButtonContent';
import {EmptyState, LoadingState} from './PageState';
import ResumeInterviewStatusBadge from './ResumeInterviewStatusBadge';
import SearchInput from './SearchInput';
import ScoreProgress from './ScoreProgress';
import StatCard from './StatCard';
import {formatDate} from '../utils/date';
import {downloadUrl} from '../utils/download';
import {formatFileSize} from '../utils/format';
import {isAnalyzeStatusFailed, shouldPollAnalyzeResult} from '../utils/analyzeStatus';
import {NORMAL_POLLING_INTERVAL_MS, useConditionalPolling} from '../hooks/useConditionalPolling';
import {
  ChevronRight,
  Download,
  Eye,
  FileStack,
  FileText,
  MessageSquare,
  RefreshCw,
  Trash2,
} from 'lucide-react';

interface HistoryListProps {
  onSelectResume: (id: number) => void;
}

export default function HistoryList({ onSelectResume }: HistoryListProps) {
  const [resumes, setResumes] = useState<ResumeListItem[]>([]);
  const [stats, setStats] = useState<ResumeStats | null>(null);
  const [loading, setLoading] = useState(true);
  const [searchTerm, setSearchTerm] = useState('');
  const [deletingId, setDeletingId] = useState<number | null>(null);
  const [deleteItem, setDeleteItem] = useState<ResumeListItem | null>(null);
  const [reanalyzingId, setReanalyzingId] = useState<number | null>(null);

  const fetchHistoryData = useCallback(() => (
    Promise.all([
      historyApi.getResumes(),
      historyApi.getStatistics(),
    ])
  ), []);

  const applyHistoryData = useCallback((
    resumeData: ResumeListItem[],
    statsData: ResumeStats,
  ) => {
    setResumes(resumeData);
    setStats(statsData);
  }, []);

  // 静默加载数据（用于轮询）
  const loadDataSilent = useCallback(async () => {
    try {
      const [resumeData, statsData] = await fetchHistoryData();
      applyHistoryData(resumeData, statsData);
    } catch (err) {
      console.error('加载数据失败', err);
    }
  }, [applyHistoryData, fetchHistoryData]);

  // 加载数据
  const loadResumes = useCallback(async () => {
    setLoading(true);
    try {
      const [resumeData, statsData] = await fetchHistoryData();
      applyHistoryData(resumeData, statsData);
    } catch (err) {
      console.error('加载数据失败', err);
    } finally {
      setLoading(false);
    }
  }, [applyHistoryData, fetchHistoryData]);

  useEffect(() => {
    loadResumes();
  }, [loadResumes]);

  // 轮询：当有待处理项时，每5秒刷新一次
  // 待处理判断：显式的 PENDING/PROCESSING 状态，或状态未定义且无分数
  const hasPendingItems = resumes.some(
    r => shouldPollAnalyzeResult(r.analyzeStatus, r.latestScore !== undefined)
  );
  useConditionalPolling(hasPendingItems && !loading, loadDataSilent, NORMAL_POLLING_INTERVAL_MS);

  // 下载简历
  const handleDownload = (resume: ResumeListItem, e: React.MouseEvent) => {
    e.stopPropagation();
    if (resume.storageUrl) {
      downloadUrl(resume.storageUrl, resume.filename);
    }
  };

  // 重新分析
  const handleReanalyze = async (id: number, e: React.MouseEvent) => {
    e.stopPropagation();
    try {
      setReanalyzingId(id);
      await historyApi.reanalyze(id);
      await loadDataSilent();
    } catch (err) {
      console.error('重新分析失败', err);
    } finally {
      setReanalyzingId(null);
    }
  };

  const handleDeleteClick = (resume: ResumeListItem, e: React.MouseEvent) => {
    e.stopPropagation();
    setDeleteItem(resume);
  };

  const handleDeleteConfirm = async () => {
    if (!deleteItem) return;

    setDeletingId(deleteItem.id);
    try {
      await historyApi.deleteResume(deleteItem.id);
      await loadResumes();
      setDeleteItem(null);
    } catch (err) {
      alert(getErrorMessage(err, '删除失败，请稍后重试'));
    } finally {
      setDeletingId(null);
    }
  };

  const filteredResumes = resumes.filter(resume =>
    resume.filename.toLowerCase().includes(searchTerm.toLowerCase())
  );

  return (
    <motion.div
      className="w-full"
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
    >
      {/* 头部 */}
      <div className="flex justify-between items-start mb-8 flex-wrap gap-6">
        <div>
          <motion.h1
            className="text-2xl font-bold text-slate-800 flex items-center gap-3"
            initial={{ opacity: 0, x: -20 }}
            animate={{ opacity: 1, x: 0 }}
          >
            <FileStack className="w-7 h-7 text-primary-500" />
            简历库
          </motion.h1>
          <motion.p
            className="text-slate-500 mt-1"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            transition={{ delay: 0.1 }}
          >
            管理您已分析过的所有简历及面试记录
          </motion.p>
        </div>

        <SearchInput
          value={searchTerm}
          onChange={setSearchTerm}
          placeholder="搜索简历..."
          className="bg-white border border-slate-200 rounded-xl px-4 py-2.5 min-w-[280px] focus-within:border-primary-500 focus-within:ring-2 focus-within:ring-primary-100 transition-all"
          inputClassName="text-slate-700 placeholder:text-slate-400"
        />
      </div>

      {/* 统计卡片 */}
      {stats && (
        <div className="grid grid-cols-1 md:grid-cols-3 gap-6 mb-8">
          <StatCard
            icon={FileStack}
            label="简历总数"
            value={stats.totalCount}
            color="bg-primary-500"
            supportDarkMode={false}
          />
          <StatCard
            icon={MessageSquare}
            label="面试总数"
            value={stats.totalInterviewCount}
            color="bg-indigo-500"
            supportDarkMode={false}
          />
          <StatCard
            icon={Eye}
            label="总访问次数"
            value={stats.totalAccessCount}
            color="bg-emerald-500"
            supportDarkMode={false}
          />
        </div>
      )}

      {/* 加载状态 */}
      {loading && (
        <LoadingState />
      )}

      {/* 空状态 */}
      {!loading && filteredResumes.length === 0 && (
        <EmptyState
          className="text-center py-20 bg-white rounded-2xl shadow-sm border border-slate-100"
          icon={FileText}
          iconClassName="w-16 h-16 text-slate-300 mx-auto mb-4"
          title="暂无简历记录"
          titleClassName="text-xl font-semibold text-slate-700 mb-2"
          description="上传简历开始您的第一次 AI 面试分析"
          descriptionClassName="text-slate-500"
        />
      )}

      {/* 表格 */}
      {!loading && filteredResumes.length > 0 && (
        <motion.div
          className="bg-white rounded-xl shadow-sm border border-slate-100 overflow-hidden"
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ delay: 0.2 }}
        >
          <table className="w-full">
            <thead className="bg-slate-50 border-b border-slate-100">
              <tr>
                <th className="text-left px-6 py-4 text-sm font-medium text-slate-600">名称</th>
                <th className="text-left px-6 py-4 text-sm font-medium text-slate-600">大小</th>
                <th className="text-left px-6 py-4 text-sm font-medium text-slate-600">分析状态</th>
                <th className="text-left px-6 py-4 text-sm font-medium text-slate-600">AI 评分</th>
                <th className="text-left px-6 py-4 text-sm font-medium text-slate-600">面试</th>
                <th className="text-left px-6 py-4 text-sm font-medium text-slate-600">上传时间</th>
                <th className="text-right px-6 py-4 text-sm font-medium text-slate-600">操作</th>
              </tr>
            </thead>
            <tbody>
              <AnimatePresence>
                {filteredResumes.map((resume, index) => (
                  <motion.tr
                    key={resume.id}
                    initial={{ opacity: 0, y: 10 }}
                    animate={{ opacity: 1, y: 0 }}
                    transition={{ delay: index * 0.05 }}
                    onClick={() => onSelectResume(resume.id)}
                    className="border-b border-slate-50 hover:bg-slate-50 cursor-pointer transition-colors group"
                  >
                    <td className="px-6 py-4">
                      <div className="flex items-center gap-3">
                        <FileText className="w-5 h-5 text-slate-400" />
                        <div>
                          <p className="font-medium text-slate-800">{resume.filename}</p>
                        </div>
                      </div>
                    </td>
                    <td className="px-6 py-4 text-sm text-slate-600">
                      {formatFileSize(resume.fileSize)}
                    </td>
                    <td className="px-6 py-4">
                      <AnalyzeStatusBadge
                        status={resume.analyzeStatus}
                        hasScore={resume.latestScore !== undefined}
                      />
                    </td>
                    <td className="px-6 py-4">
                      {resume.latestScore !== undefined ? (
                        <ScoreProgress
                          score={resume.latestScore}
                          delay={index * 0.05}
                          supportDarkMode={false}
                          valueClassName="font-bold text-slate-800"
                        />
                      ) : (
                        <span className="text-slate-400">-</span>
                      )}
                    </td>
                    <td className="px-6 py-4">
                      <ResumeInterviewStatusBadge
                        interviewCount={resume.interviewCount}
                        supportDarkMode={false}
                      />
                    </td>
                    <td className="px-6 py-4 text-sm text-slate-500">
                      {formatDate(resume.uploadedAt)}
                    </td>
                    <td className="px-6 py-4 text-right">
                      <div className="flex items-center justify-end gap-1">
                        {/* 下载按钮 */}
                        {resume.storageUrl && (
                          <button
                            onClick={(e) => handleDownload(resume, e)}
                            className="p-2 text-slate-400 hover:text-primary-500 hover:bg-primary-50 rounded-lg transition-colors"
                            title="下载"
                          >
                            <Download className="w-4 h-4" />
                          </button>
                        )}
                        {/* 重新分析按钮（仅 FAILED 状态显示） */}
                        {isAnalyzeStatusFailed(resume.analyzeStatus) && (
                          <button
                            onClick={(e) => handleReanalyze(resume.id, e)}
                            disabled={reanalyzingId === resume.id}
                            className="p-2 text-slate-400 hover:text-primary-500 hover:bg-primary-50 rounded-lg transition-colors disabled:opacity-50"
                            title="重新分析"
                          >
                            <LoadingButtonContent
                              loading={reanalyzingId === resume.id}
                              loadingText="重新分析中"
                              iconOnly
                            >
                              <RefreshCw className="w-4 h-4" />
                            </LoadingButtonContent>
                          </button>
                        )}
                        {/* 删除按钮 */}
                        <button
                          onClick={(e) => handleDeleteClick(resume, e)}
                          disabled={deletingId === resume.id}
                          className="p-2 text-slate-400 hover:text-red-500 hover:bg-red-50 rounded-lg transition-colors disabled:opacity-50"
                          title="删除"
                        >
                          <Trash2 className="w-4 h-4" />
                        </button>
                        <ChevronRight className="w-5 h-5 text-slate-300 group-hover:text-primary-500 group-hover:translate-x-1 transition-all" />
                      </div>
                    </td>
                  </motion.tr>
                ))}
              </AnimatePresence>
            </tbody>
          </table>
        </motion.div>
      )}

      {/* 删除确认对话框 */}
      <DeleteConfirmDialog
        open={deleteItem !== null}
        item={deleteItem ? { id: deleteItem.id, name: deleteItem.filename } : null}
        itemType="简历"
        loading={deletingId !== null}
        onConfirm={handleDeleteConfirm}
        onCancel={() => setDeleteItem(null)}
      />
    </motion.div>
  );
}
