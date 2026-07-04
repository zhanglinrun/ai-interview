import {useCallback, useEffect, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {AnimatePresence, motion} from 'framer-motion';
import {ChevronRight, FileText, Mic2, Trash2, Upload} from 'lucide-react';
import {historyApi, ResumeListItem} from '../api/history';
import {getErrorMessage} from '../api/request';
import AnalyzeStatusBadge from '../components/AnalyzeStatusBadge';
import DeleteConfirmDialog from '../components/DeleteConfirmDialog';
import LoadingButtonContent from '../components/LoadingButtonContent';
import {EmptyState, LoadingState} from '../components/PageState';
import PageHeader from '../components/ui/PageHeader';
import ResumeInterviewStatusBadge from '../components/ResumeInterviewStatusBadge';
import SearchInput from '../components/SearchInput';
import ScoreProgress from '../components/ScoreProgress';
import {formatDateOnly} from '../utils/date';
import {
  isAnalyzeStatusFailed,
  isAnalyzeStatusProcessing,
  hasCompletedAnalyzeResult,
  shouldPollAnalyzeResult,
} from '../utils/analyzeStatus';
import { ROUTES } from '../constants/routes';
import {FAST_POLLING_INTERVAL_MS, useConditionalPolling} from '../hooks/useConditionalPolling';

interface HistoryPageProps {
  onSelectResume: (id: number) => void;
}

function resumesEqual(a: ResumeListItem[], b: ResumeListItem[]): boolean {
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) {
    if (a[i].id !== b[i].id ||
        a[i].analyzeStatus !== b[i].analyzeStatus ||
        a[i].latestScore !== b[i].latestScore) return false;
  }
  return true;
}

export default function HistoryPage({onSelectResume}: HistoryPageProps) {
  const navigate = useNavigate();
  const [resumes, setResumes] = useState<ResumeListItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [searchTerm, setSearchTerm] = useState('');
  const [deletingId, setDeletingId] = useState<number | null>(null);
  const [deleteConfirm, setDeleteConfirm] = useState<{ id: number; filename: string } | null>(null);

  const loadResumes = useCallback(async (isPolling = false) => {
    if (!isPolling) setLoading(true);
    try {
      const data = await historyApi.getResumes();
      setResumes(prev => {
        if (isPolling && resumesEqual(prev, data)) return prev;
        return data;
      });
    } catch (err) {
      console.error('加载历史记录失败', err);
    } finally {
      if (!isPolling) setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadResumes();
  }, [loadResumes]);

  // 轮询：有分析中的简历时启动 3s 轮询
  const hasAnalyzing = resumes.some(
    r => shouldPollAnalyzeResult(r.analyzeStatus, r.latestScore !== undefined)
  );

  useConditionalPolling(hasAnalyzing, () => loadResumes(true), FAST_POLLING_INTERVAL_MS);

  const handleDeleteClick = (id: number, filename: string, e: React.MouseEvent) => {
    e.stopPropagation();
    setDeleteConfirm({id, filename});
  };

  const handleDeleteConfirm = async () => {
    if (!deleteConfirm) return;

    const {id} = deleteConfirm;
    setDeletingId(id);
    try {
      await historyApi.deleteResume(id);
      await loadResumes();
      setDeleteConfirm(null);
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
      initial={{opacity: 0}}
      animate={{opacity: 1}}
    >
      <PageHeader
        eyebrow="面试准备"
        title="简历管理"
        description="上传并管理简历，查看 AI 分析与评分结果。"
        action={
          <div className="flex gap-2">
            <button
              onClick={() => navigate(ROUTES.resumeUpload)}
              className="inline-flex items-center gap-2 px-4 py-2.5 btn-primary rounded-lg text-sm font-medium"
            >
              <Upload className="w-4 h-4" />
              上传简历
            </button>
            <button
              onClick={() => navigate('/interview-hub')}
              className="inline-flex items-center gap-2 px-4 py-2.5 btn-secondary rounded-lg text-sm font-medium"
            >
              <Mic2 className="w-4 h-4" />
              模拟面试
            </button>
          </div>
        }
      />

      {/* 搜索栏 */}
      <div className="mb-6">
        <SearchInput
          value={searchTerm}
          onChange={setSearchTerm}
          placeholder="搜索简历..."
          className="bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-600 rounded-xl px-4 py-3 max-w-md focus-within:border-primary-500 focus-within:ring-2 focus-within:ring-primary-100 transition-all"
          animated={false}
        />
      </div>

      {/* 加载状态 */}
      {loading && (
        <LoadingState
          label="加载中..."
          className="text-center py-20"
          spinnerClassName="w-10 h-10 text-primary-500 animate-spin mx-auto mb-4"
        />
      )}

      {/* 空状态 */}
      {!loading && filteredResumes.length === 0 && (
        <EmptyState
          className="text-center py-20 bg-white dark:bg-slate-800 rounded-2xl"
          iconNode={<div className="text-6xl mb-6">📄</div>}
          title="暂无简历记录"
          description="上传简历开始您的第一次 AI 面试分析"
        />
      )}

      {/* 表格 */}
      {!loading && filteredResumes.length > 0 && (
        <motion.div
          className="bg-white dark:bg-slate-800 rounded-2xl shadow-sm overflow-hidden"
          initial={{opacity: 0, y: 20}}
          animate={{opacity: 1, y: 0}}
          transition={{delay: 0.2}}
        >
          <table className="w-full">
            <thead>
            <tr className="bg-slate-50 dark:bg-slate-700/50 border-b border-slate-100 dark:border-slate-600">
              <th className="text-left px-6 py-4 text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wide">简历名称</th>
              <th className="text-left px-6 py-4 text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wide">上传日期</th>
              <th className="text-left px-6 py-4 text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wide">分析状态</th>
              <th className="text-left px-6 py-4 text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wide">AI 评分</th>
              <th className="text-left px-6 py-4 text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wide">面试状态</th>
              <th className="w-20"></th>
            </tr>
            </thead>
            <tbody>
            <AnimatePresence>
              {filteredResumes.map((resume, index) => (
                <motion.tr
                  key={resume.id}
                  initial={{opacity: 0, x: -20}}
                  animate={{opacity: 1, x: 0}}
                  transition={{delay: index * 0.05}}
                  onClick={() => onSelectResume(resume.id)}
                  className="border-b border-slate-100 dark:border-slate-700 last:border-0 hover:bg-slate-50 dark:hover:bg-slate-700 cursor-pointer transition-colors group"
                >
                  <td className="px-6 py-5">
                    <div className="flex items-center gap-4">
                      <div
                        className="w-10 h-10 bg-primary-50 dark:bg-primary-900/30 rounded-xl flex items-center justify-center text-primary-500 dark:text-primary-400">
                        <FileText className="w-5 h-5" />
                      </div>
                      <span className="font-medium text-slate-800 dark:text-white">{resume.filename}</span>
                    </div>
                  </td>
                  <td className="px-6 py-5 text-slate-500 dark:text-slate-400">{formatDateOnly(resume.uploadedAt)}</td>
                  <td className="px-6 py-5">
                    <AnalyzeStatusBadge
                      status={resume.analyzeStatus}
                      hasScore={resume.latestScore !== undefined}
                      textMode="detail"
                      spinner="refresh"
                      darkMode
                      textClassName="text-sm text-slate-600 dark:text-slate-300"
                    />
                  </td>
                  <td className="px-6 py-5">
                    {hasCompletedAnalyzeResult(
                      resume.analyzeStatus,
                      resume.latestScore !== undefined,
                    ) && resume.latestScore !== undefined ? (
                      <ScoreProgress
                        score={resume.latestScore}
                        delay={index * 0.05}
                        widthClassName="w-20"
                      />
                    ) : isAnalyzeStatusProcessing(resume.analyzeStatus) ? (
                      <span className="text-blue-500 dark:text-blue-400 text-sm">生成中...</span>
                    ) : isAnalyzeStatusFailed(resume.analyzeStatus) ? (
                      <span className="text-red-500 dark:text-red-400 text-sm"
                            title={resume.analyzeError}>失败</span>
                    ) : (
                      <span className="text-slate-400 dark:text-slate-500">-</span>
                    )}
                  </td>
                  <td className="px-6 py-5">
                    <ResumeInterviewStatusBadge
                      interviewCount={resume.interviewCount}
                      completedLabel="已完成"
                    />
                  </td>
                  <td className="px-4">
                    <div className="flex items-center gap-2">
                      <button
                        onClick={(e) => handleDeleteClick(resume.id, resume.filename, e)}
                        disabled={deletingId === resume.id}
                        className="p-2 text-slate-400 hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-900/30 rounded-lg transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                        title="删除简历"
                      >
                        <LoadingButtonContent
                          loading={deletingId === resume.id}
                          loadingText="删除中"
                          iconOnly
                          spinnerClassName="w-5 h-5 animate-spin text-red-500"
                        >
                          <Trash2 className="w-5 h-5" />
                        </LoadingButtonContent>
                      </button>
                      <ChevronRight className="w-5 h-5 text-slate-300 dark:text-slate-600 group-hover:text-primary-500 group-hover:translate-x-1 transition-all" />
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
        open={deleteConfirm !== null}
        item={deleteConfirm}
        itemType="简历"
        loading={deletingId !== null}
        onConfirm={handleDeleteConfirm}
        onCancel={() => setDeleteConfirm(null)}
        customMessage={
          deleteConfirm ? (
            <>
              <p className="mb-2">确定要删除简历 <strong>"{deleteConfirm.filename}"</strong> 吗？</p>
              <p className="text-sm text-slate-500 dark:text-slate-400 mb-2">删除后将同时删除：</p>
              <ul className="text-sm text-slate-500 dark:text-red-400 list-disc list-inside mb-2">
                <li>简历评价记录</li>
                <li>所有模拟面试记录</li>
              </ul>
              <p className="text-sm font-semibold text-red-600">此操作不可恢复！</p>
            </>
          ) : undefined
        }
      />
    </motion.div>
  );
}
