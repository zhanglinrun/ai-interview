import {useCallback, useEffect, useRef, useState} from 'react';
import {
  Check,
  ChevronDown,
  Database,
  Download,
  Edit3,
  Eye,
  FileText,
  HardDrive,
  Layers,
  History,
  MessageSquare,
  RefreshCw,
  Trash2,
  Upload,
  X,
} from 'lucide-react';
import {
  knowledgeBaseApi,
  KnowledgeBaseItem,
  KnowledgeBaseSegment,
  KnowledgeBaseStats,
  KnowledgeBaseVersion,
  RagQueryTrace,
  SortOption,
} from '../api/knowledgebase';
import {getErrorMessage} from '../api/request';
import DeleteConfirmDialog from '../components/DeleteConfirmDialog';
import KnowledgeBaseSortSelect from '../components/KnowledgeBaseSortSelect';
import LoadingButtonContent from '../components/LoadingButtonContent';
import {EmptyState, LoadingState} from '../components/PageState';
import SearchInput from '../components/SearchInput';
import StatCard from '../components/StatCard';
import VectorStatusBadge from '../components/VectorStatusBadge';
import PageHeader from '../components/ui/PageHeader';
import {formatDateTime} from '../utils/date';
import {downloadBlob} from '../utils/download';
import {formatFileSize} from '../utils/format';
import {isVectorStatusFailed, isVectorStatusProcessing} from '../utils/vectorStatus';
import {NORMAL_POLLING_INTERVAL_MS, useConditionalPolling} from '../hooks/useConditionalPolling';
import {dismissKbUploadQueueSummary, getKbUploadQueueSummary, subscribeKbUploadQueue} from '../stores/kbUploadQueue';

interface KnowledgeBaseManagePageProps {
  onUpload: () => void;
  onChat: () => void;
}

interface TraceContent {
  rank: number;
  docId: string | null;
  chunkId: string | null;
  score: number | null;
  rerankScore: number | null;
  snippet: string;
}

function parseTraceList(json: string | null): TraceContent[] {
  if (!json) return [];
  try {
    const parsed = JSON.parse(json);
    return Array.isArray(parsed) ? parsed as TraceContent[] : [];
  } catch {
    return [];
  }
}

function parseJsonList(json: string | null | undefined): string[] {
  if (!json) return [];
  try {
    const parsed = JSON.parse(json);
    return Array.isArray(parsed) ? parsed.map(String) : [];
  } catch {
    return [];
  }
}

function segmentKindLabel(seg: KnowledgeBaseSegment): string {
  if (seg.skipEmbedding === 1) return '父分段 · 不入库向量';
  if (seg.parentChunkId) return '子分段 · 检索';
  return '检索段';
}

function parseSourceList(json: string | null): Array<{ documentTitle?: string; snippet?: string; similarity?: number | null }> {
  if (!json) return [];
  try {
    const parsed = JSON.parse(json);
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

export default function KnowledgeBaseManagePage({ onUpload, onChat }: KnowledgeBaseManagePageProps) {
  const [stats, setStats] = useState<KnowledgeBaseStats | null>(null);
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBaseItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [searchKeyword, setSearchKeyword] = useState('');
  const [sortBy, setSortBy] = useState<SortOption>('time');
  const [selectedCategory, setSelectedCategory] = useState<string | null>(null);
  const [categories, setCategories] = useState<string[]>([]);
  const [deleteItem, setDeleteItem] = useState<KnowledgeBaseItem | null>(null);
  const [deleting, setDeleting] = useState(false);

  // 分类编辑状态
  const [editingCategoryId, setEditingCategoryId] = useState<number | null>(null);
  const [editingCategoryValue, setEditingCategoryValue] = useState('');
  const [savingCategory, setSavingCategory] = useState(false);
  const categoryInputRef = useRef<HTMLInputElement>(null);

  // 重新向量化状态
  const [revectorizing, setRevectorizing] = useState<number | null>(null);
  const [splitModalKb, setSplitModalKb] = useState<KnowledgeBaseItem | null>(null);
  const [splitType, setSplitType] = useState('TITLE');
  const [splitChunkSize, setSplitChunkSize] = useState(800);
  const [splitOverlap, setSplitOverlap] = useState(80);
  const [splitTitleLevel, setSplitTitleLevel] = useState(3);
  const [splitting, setSplitting] = useState(false);

  // 版本管理状态
  const [versionModalKb, setVersionModalKb] = useState<KnowledgeBaseItem | null>(null);
  const [versions, setVersions] = useState<KnowledgeBaseVersion[]>([]);
  const [versionsLoading, setVersionsLoading] = useState(false);
  const [switchingVersionId, setSwitchingVersionId] = useState<number | null>(null);
  const [versionUploadFile, setVersionUploadFile] = useState<File | null>(null);
  const [versionChangelog, setVersionChangelog] = useState('');
  const [versionUploading, setVersionUploading] = useState(false);
  const [versionUploadError, setVersionUploadError] = useState('');
  const [traceOpen, setTraceOpen] = useState(false);
  const [traces, setTraces] = useState<RagQueryTrace[]>([]);
  const [tracesLoading, setTracesLoading] = useState(false);
  const [expandedTraceId, setExpandedTraceId] = useState<string | null>(null);
  const [traceDetail, setTraceDetail] = useState<RagQueryTrace | null>(null);
  const [traceDetailLoading, setTraceDetailLoading] = useState(false);
  const [segmentModalKb, setSegmentModalKb] = useState<KnowledgeBaseItem | null>(null);
  const [segments, setSegments] = useState<KnowledgeBaseSegment[]>([]);
  const [segmentTotal, setSegmentTotal] = useState(0);
  const [segmentPage, setSegmentPage] = useState(1);
  const [segmentsLoading, setSegmentsLoading] = useState(false);
  const [versionActionId, setVersionActionId] = useState<number | null>(null);

  const UNCategorized_KEY = '__uncategorized__';

  const fetchPageData = useCallback(async () => {
    const kbListPromise = searchKeyword
      ? knowledgeBaseApi.search(searchKeyword)
      : selectedCategory === UNCategorized_KEY
      ? knowledgeBaseApi.getUncategorized()
      : selectedCategory
      ? knowledgeBaseApi.getByCategory(selectedCategory)
      : knowledgeBaseApi.getAllKnowledgeBases(sortBy);

    return Promise.all([
      knowledgeBaseApi.getStatistics(),
      kbListPromise,
      knowledgeBaseApi.getAllCategories(),
    ]);
  }, [searchKeyword, sortBy, selectedCategory]);

  const applyPageData = useCallback((
    statsData: KnowledgeBaseStats,
    kbList: KnowledgeBaseItem[],
    categoryList: string[]
  ) => {
    setStats(statsData);
    setKnowledgeBases(kbList);
    setCategories(categoryList);
  }, []);

  // 加载数据（不显示loading状态，用于轮询）
  const loadDataSilent = useCallback(async () => {
    try {
      const [statsData, kbList, categoryList] = await fetchPageData();
      applyPageData(statsData, kbList, categoryList);
    } catch (error) {
      console.error('加载数据失败:', error);
    }
  }, [applyPageData, fetchPageData]);

  // 加载数据
  const loadData = useCallback(async () => {
    try {
      setLoading(true);
      const [statsData, kbList, categoryList] = await fetchPageData();
      applyPageData(statsData, kbList, categoryList);
    } catch (error) {
      console.error('加载数据失败:', error);
    } finally {
      setLoading(false);
    }
  }, [applyPageData, fetchPageData]);

  useEffect(() => {
    loadData();
  }, [loadData]);

  const [queueSummary, setQueueSummary] = useState(getKbUploadQueueSummary);

  useEffect(() => subscribeKbUploadQueue(() => {
    setQueueSummary(getKbUploadQueueSummary());
  }), []);

  useEffect(() => {
    if (queueSummary.accepted > 0) {
      void loadDataSilent();
    }
  }, [queueSummary.accepted, loadDataSilent]);

  // 轮询：当有处理中文档或浏览器还在逐个投递时，每5秒刷新一次
  const hasPendingItems = knowledgeBases.some(
    kb => isVectorStatusProcessing(kb.docStatus)
  );
  useConditionalPolling(
    (hasPendingItems || queueSummary.active) && !loading,
    loadDataSilent,
    NORMAL_POLLING_INTERVAL_MS,
  );

  // 重新向量化
  const handleRevectorize = async (id: number) => {
    try {
      setRevectorizing(id);
      await knowledgeBaseApi.revectorize(id);
      await loadDataSilent();
    } catch (error) {
      console.error('重新向量化失败:', error);
    } finally {
      setRevectorizing(null);
    }
  };

  const handleSplitDocument = async () => {
    if (!splitModalKb) return;
    try {
      setSplitting(true);
      await knowledgeBaseApi.splitDocument(splitModalKb.id, {
        splitType,
        chunkSize: splitChunkSize,
        overlap: splitOverlap,
        titleLevel: splitTitleLevel,
      });
      setSplitModalKb(null);
      await loadDataSilent();
    } catch (error) {
      console.error('切块失败:', error);
    } finally {
      setSplitting(false);
    }
  };

  // 打开版本管理弹窗
  const handleShowVersions = async (kb: KnowledgeBaseItem) => {
    setVersionModalKb(kb);
    try {
      setVersionsLoading(true);
      setVersions(await knowledgeBaseApi.listVersions(kb.id));
    } catch (error) {
      console.error('加载版本列表失败:', error);
      setVersions([]);
    } finally {
      setVersionsLoading(false);
    }
  };

  // 关闭版本管理弹窗
  const handleCloseVersions = () => {
    setVersionModalKb(null);
    setVersions([]);
    setSwitchingVersionId(null);
    setVersionUploadFile(null);
    setVersionChangelog('');
    setVersionUploading(false);
    setVersionUploadError('');
  };

  const handleUploadNewVersion = async () => {
    if (!versionModalKb || !versionUploadFile) return;
    try {
      setVersionUploading(true);
      setVersionUploadError('');
      await knowledgeBaseApi.uploadNewVersion(
        versionModalKb.id,
        versionUploadFile,
        versionChangelog,
      );
      setVersionUploadFile(null);
      setVersionChangelog('');
      setVersions(await knowledgeBaseApi.listVersions(versionModalKb.id));
      await loadDataSilent();
    } catch (error) {
      console.error('上传新版本失败:', error);
      setVersionUploadError(getErrorMessage(error, '上传新版本失败，请检查后端日志'));
    } finally {
      setVersionUploading(false);
    }
  };

  // 切换当前激活版本
  const handleSwitchVersion = async (versionId: number) => {
    if (!versionModalKb) return;
    try {
      setSwitchingVersionId(versionId);
      await knowledgeBaseApi.switchVersion(versionModalKb.id, versionId);
      // 切换后刷新版本列表 + 主列表（currentVersionId / docStatus 可能变化）
      setVersions(await knowledgeBaseApi.listVersions(versionModalKb.id));
      await loadDataSilent();
    } catch (error) {
      console.error('切换版本失败:', error);
    } finally {
      setSwitchingVersionId(null);
    }
  };

  const handleActivateVersion = async (versionId: number) => {
    if (!versionModalKb) return;
    try {
      setVersionActionId(versionId);
      await knowledgeBaseApi.activateVersion(versionModalKb.id, versionId);
      setVersions(await knowledgeBaseApi.listVersions(versionModalKb.id));
      await loadDataSilent();
    } catch (error) {
      console.error('激活版本失败:', error);
    } finally {
      setVersionActionId(null);
    }
  };

  const handleDeactivateVersion = async (versionId: number) => {
    if (!versionModalKb) return;
    try {
      setVersionActionId(versionId);
      await knowledgeBaseApi.deactivateVersion(versionModalKb.id, versionId);
      setVersions(await knowledgeBaseApi.listVersions(versionModalKb.id));
      await loadDataSilent();
    } catch (error) {
      console.error('失效版本失败:', error);
    } finally {
      setVersionActionId(null);
    }
  };

  const handleShowSegments = async (kb: KnowledgeBaseItem, page = 1) => {
    setSegmentModalKb(kb);
    setSegmentPage(page);
    try {
      setSegmentsLoading(true);
      const result = await knowledgeBaseApi.pageSegments(kb.id, page, 20, kb.currentVersionId ?? undefined);
      setSegments(result.records);
      setSegmentTotal(result.total);
    } catch (error) {
      console.error('加载分段失败:', error);
      setSegments([]);
      setSegmentTotal(0);
    } finally {
      setSegmentsLoading(false);
    }
  };

  const handleExpandTrace = async (traceId: string) => {
    if (expandedTraceId === traceId) {
      setExpandedTraceId(null);
      setTraceDetail(null);
      return;
    }
    setExpandedTraceId(traceId);
    setTraceDetailLoading(true);
    try {
      setTraceDetail(await knowledgeBaseApi.getTrace(traceId));
    } catch (error) {
      console.error('加载 Trace 详情失败:', error);
      setTraceDetail(null);
    } finally {
      setTraceDetailLoading(false);
    }
  };

  const handleShowTraces = async () => {
    setTraceOpen(true);
    setTracesLoading(true);
    try {
      setTraces(await knowledgeBaseApi.listTraces(20));
    } catch (error) {
      console.error('加载 RAG Trace 失败:', error);
      setTraces([]);
    } finally {
      setTracesLoading(false);
    }
  };

  // 删除知识库
  const handleDelete = async () => {
    if (!deleteItem) return;
    try {
      setDeleting(true);
      await knowledgeBaseApi.deleteKnowledgeBase(deleteItem.id);
      setDeleteItem(null);
      await loadData();
    } catch (error) {
      console.error('删除失败:', error);
    } finally {
      setDeleting(false);
    }
  };

  // 下载知识库
    const handleDownload = async (kb: KnowledgeBaseItem) => {
        try {
            const blob = await knowledgeBaseApi.downloadKnowledgeBase(kb.id);
            downloadBlob(blob, kb.originalFilename);
        } catch (error) {
            console.error('下载失败:', error);
        }
  };

  // 开始编辑分类
  const handleStartEditCategory = (kb: KnowledgeBaseItem) => {
    setEditingCategoryId(kb.id);
    setEditingCategoryValue(kb.category || '');
    setTimeout(() => {
      categoryInputRef.current?.focus();
    }, 50);
  };

  // 取消编辑分类
  const handleCancelEditCategory = () => {
    setEditingCategoryId(null);
    setEditingCategoryValue('');
  };

  // 保存分类
  const handleSaveCategory = async (id: number) => {
    try {
      setSavingCategory(true);
      const categoryToSave = editingCategoryValue.trim() || null;
      await knowledgeBaseApi.updateCategory(id, categoryToSave);
      setEditingCategoryId(null);
      setEditingCategoryValue('');
      await loadData();
    } catch (error) {
      console.error('更新分类失败:', error);
    } finally {
      setSavingCategory(false);
    }
  };

  // 处理分类输入框按键
  const handleCategoryKeyDown = (e: React.KeyboardEvent, id: number) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      handleSaveCategory(id);
    } else if (e.key === 'Escape') {
      handleCancelEditCategory();
    }
  };

  // 搜索处理
  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault();
    loadData();
  };

  return (
    <div className="max-w-7xl mx-auto">
      <PageHeader
        eyebrow="我的资料"
        title="知识库"
        description="管理已上传的资料，查看处理状态并进入知识问答。"
        action={(
          <div className="flex flex-wrap gap-2">
            <button onClick={onUpload} className="btn-primary flex items-center gap-2 px-4 py-2 text-sm">
              <Upload className="w-4 h-4" />
              上传资料
            </button>
            <button onClick={handleShowTraces} className="btn-secondary flex items-center gap-2 px-4 py-2 text-sm">
              <Eye className="w-4 h-4" />
              检索记录
            </button>
            <button onClick={onChat} className="btn-secondary flex items-center gap-2 px-4 py-2 text-sm">
              <MessageSquare className="w-4 h-4" />
              知识问答
            </button>
          </div>
        )}
      />
      {(queueSummary.active || queueSummary.failed > 0) && (
        <div className="relative mb-4 rounded-lg border border-amber-200 dark:border-amber-900/40 bg-amber-50 dark:bg-amber-950/20 px-4 py-3 pr-10 text-sm text-amber-800 dark:text-amber-200">
          {queueSummary.active
            ? `后台上传进行中：已接收 ${queueSummary.accepted}/${queueSummary.total}，队列中 ${queueSummary.pending} 个（最多 2 路并行）。请勿关闭或刷新本标签页，否则尚未发出的文件会中断。`
            : `后台上传结束：成功 ${queueSummary.accepted}，失败 ${queueSummary.failed}。`}
          {queueSummary.failedItems.length > 0 && (
            <ul className="mt-2 space-y-1 text-xs">
              {queueSummary.failedItems.map((item) => (
                <li key={item.id}>
                  {item.fileName}
                  {item.error ? `：${item.error}` : ''}
                </li>
              ))}
            </ul>
          )}
          {!queueSummary.active && (
            <button
              type="button"
              onClick={dismissKbUploadQueueSummary}
              className="absolute right-2 top-2 p-1.5 text-amber-700/70 hover:text-amber-900 dark:text-amber-300/70 dark:hover:text-amber-100 hover:bg-amber-100 dark:hover:bg-amber-900/40 rounded-lg transition-colors"
              aria-label="关闭上传结果"
            >
              <X className="w-4 h-4" />
            </button>
          )}
        </div>
      )}
      {/* 统计卡片 */}
      {stats && (
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4 mb-6">
          <StatCard
            icon={Database}
            label="知识库总数"
            value={stats.totalCount}
            color="bg-primary-500"
          />
          <StatCard
            icon={MessageSquare}
            label="提问次数"
            value={stats.totalQuestionCount}
            color="bg-indigo-500"
          />
          <StatCard
            icon={Eye}
            label="访问次数"
            value={stats.totalAccessCount}
            color="bg-emerald-500"
          />
        </div>
      )}

      {/* 搜索和筛选栏 */}
        <div className="surface-card p-3 mb-4">
        <div className="flex flex-wrap items-center gap-4">
          {/* 搜索框 */}
          <form onSubmit={handleSearch} className="flex-1 min-w-[200px]">
            <SearchInput
              value={searchKeyword}
              onChange={setSearchKeyword}
              placeholder="搜索知识库名称..."
              className="w-full px-4 py-2 border border-slate-200 dark:border-slate-600 rounded-lg focus-within:ring-2 focus-within:ring-primary-500 focus-within:border-transparent bg-white dark:bg-slate-700"
              iconClassName="w-4 h-4 text-slate-400"
              inputClassName="text-slate-900 dark:text-white placeholder:text-slate-400"
            />
          </form>

          {/* 排序选择 */}
          <KnowledgeBaseSortSelect
            value={sortBy}
            onChange={(value) => {
              setSortBy(value);
              setSearchKeyword('');
              setSelectedCategory(null);
            }}
          />

          {/* 分类筛选 */}
          <div className="relative">
            <select
              value={selectedCategory || ''}
              onChange={(e) => {
                setSelectedCategory(e.target.value || null);
                setSearchKeyword('');
              }}
              className="appearance-none pl-4 pr-10 py-2 border border-slate-200 dark:border-slate-600 rounded-lg focus:outline-none focus:ring-2 focus:ring-primary-500 bg-white dark:bg-slate-700 text-slate-900 dark:text-white cursor-pointer"
            >
              <option value="">全部分类</option>
              <option value={UNCategorized_KEY}>未分类</option>
              {categories.map((cat) => (
                <option key={cat} value={cat}>
                  {cat}
                </option>
                              ))}
            </select>
            <ChevronDown className="absolute right-3 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400 pointer-events-none" />
          </div>
        </div>
      </div>

      {/* 知识库列表 */}
        <div className="surface-card overflow-x-auto">
        {loading ? (
          <LoadingState />
        ) : knowledgeBases.length === 0 ? (
          <EmptyState
            className="text-center py-20"
            icon={HardDrive}
            title="暂无知识库"
            titleClassName="text-slate-500 dark:text-slate-400"
            action={(
              <button
                onClick={onUpload}
                className="mt-4 text-primary-500 hover:text-primary-600"
              >
                上传第一份资料
              </button>
            )}
          />
        ) : (
          <table className="w-full min-w-[980px]">
              <thead className="bg-slate-50 dark:bg-slate-700 border-b border-slate-100 dark:border-slate-600">
              <tr>
                  <th className="text-left px-6 py-4 text-sm font-medium text-slate-600 dark:text-slate-300">
                  名称
                </th>
                  <th className="text-left px-6 py-4 text-sm font-medium text-slate-600 dark:text-slate-300">
                  分类
                </th>
                  <th className="text-left px-6 py-4 text-sm font-medium text-slate-600 dark:text-slate-300">
                  大小
                </th>
                  <th className="text-left px-6 py-4 text-sm font-medium text-slate-600 dark:text-slate-300">
                  状态
                </th>
                  <th className="text-left px-6 py-4 text-sm font-medium text-slate-600 dark:text-slate-300">
                  提问
                </th>
                  <th className="text-left px-6 py-4 text-sm font-medium text-slate-600 dark:text-slate-300">
                  上传时间
                </th>
                  <th className="text-right px-6 py-4 text-sm font-medium text-slate-600 dark:text-slate-300">
                  操作
                </th>
              </tr>
            </thead>
            <tbody>
              {knowledgeBases.map((kb) => (
                <tr
                  key={kb.id}
                  className="border-b border-slate-50 dark:border-slate-700 hover:bg-slate-50 dark:hover:bg-slate-700/50 transition-colors"
                >
                  <td className="px-6 py-4">
                    <div className="flex items-center gap-3">
                      <FileText className="w-5 h-5 text-slate-400" />
                      <div>
                          <p className="font-medium text-slate-800 dark:text-white">{kb.name}</p>
                          <div className="flex flex-wrap items-center gap-1.5 mt-0.5">
                            <p className="text-xs text-slate-400 dark:text-slate-500">{kb.originalFilename}</p>
                            {kb.accessibleBy === 'PUBLIC' && (
                              <span className="px-1.5 py-0.5 text-[10px] rounded bg-sky-100 text-sky-700 dark:bg-sky-900/40 dark:text-sky-300">
                                公开
                              </span>
                            )}
                            {kb.owned === false && (
                              <span className="px-1.5 py-0.5 text-[10px] rounded bg-slate-200 text-slate-600 dark:bg-slate-700 dark:text-slate-300">
                                只读
                              </span>
                            )}
                          </div>
                      </div>
                    </div>
                  </td>
                  <td className="px-6 py-4">
                      {editingCategoryId === kb.id ? (
                        <div className="flex items-center gap-2">
                          <input
                            ref={categoryInputRef}
                            type="text"
                            value={editingCategoryValue}
                            onChange={(e) => setEditingCategoryValue(e.target.value)}
                            onKeyDown={(e) => handleCategoryKeyDown(e, kb.id)}
                            placeholder="输入分类名称"
                            list="category-suggestions"
                            className="w-24 px-2 py-1 text-sm border border-primary-300 dark:border-primary-600 rounded focus:outline-none focus:ring-2 focus:ring-primary-500 bg-white dark:bg-slate-700 text-slate-900 dark:text-white"
                            disabled={savingCategory}
                          />
                          <datalist id="category-suggestions">
                            {categories.map((cat) => (
                              <option key={cat} value={cat} />
                            ))}
                          </datalist>
                          <button
                            onClick={() => handleSaveCategory(kb.id)}
                            disabled={savingCategory}
                            className="p-1 text-green-600 dark:text-green-400 hover:bg-green-50 dark:hover:bg-green-900/20 rounded transition-colors disabled:opacity-50"
                            title="保存"
                          >
                            <LoadingButtonContent
                              loading={savingCategory}
                              loadingText="保存中"
                              iconOnly
                            >
                              <Check className="w-4 h-4" />
                            </LoadingButtonContent>
                          </button>
                          <button
                            onClick={handleCancelEditCategory}
                            disabled={savingCategory}
                            className="p-1 text-slate-400 hover:text-slate-600 dark:hover:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-600 rounded transition-colors disabled:opacity-50"
                            title="取消"
                          >
                            <X className="w-4 h-4" />
                          </button>
                        </div>
                      ) : (
                        <div className="flex items-center gap-2 group/category">
                          {kb.category ? (
                              <span
                                  className="px-2 py-1 bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300 rounded text-sm">
                              {kb.category}
                            </span>
                          ) : (
                              <span className="text-slate-400 dark:text-slate-500 text-sm">未分类</span>
                          )}
                          <button
                            onClick={() => handleStartEditCategory(kb)}
                            className="p-1 text-slate-400 hover:text-primary-500 hover:bg-primary-50 dark:hover:bg-primary-900/30 rounded opacity-70 hover:opacity-100 focus-visible:opacity-100 transition-colors"
                            title="编辑分类"
                          >
                            <Edit3 className="w-3.5 h-3.5" />
                          </button>
                        </div>
                      )}
                  </td>
                    <td className="px-6 py-4 text-sm text-slate-600 dark:text-slate-300">
                    {formatFileSize(kb.fileSize)}
                  </td>
                  <td className="px-6 py-4">
                    <VectorStatusBadge
                      status={kb.docStatus}
                      textClassName="text-sm text-slate-600 dark:text-slate-300"
                    />
                  </td>
                    <td className="px-6 py-4 text-sm text-slate-600 dark:text-slate-300">
                    {kb.questionCount}
                  </td>
                    <td className="px-6 py-4 text-sm text-slate-500 dark:text-slate-400">
                    {formatDateTime(kb.uploadedAt)}
                  </td>
                  <td className="px-6 py-4 text-right">
                    <div className="flex items-center justify-end gap-1">
                      {/* 版本管理按钮 */}
                      <button
                        onClick={() => handleShowVersions(kb)}
                        className="p-2 text-slate-400 hover:text-primary-500 hover:bg-primary-50 dark:hover:bg-primary-900/30 rounded-lg transition-colors"
                        title="版本管理"
                      >
                        <History className="w-4 h-4" />
                      </button>
                      <button
                        onClick={() => handleShowSegments(kb)}
                        className="p-2 text-slate-400 hover:text-primary-500 hover:bg-primary-50 dark:hover:bg-primary-900/30 rounded-lg transition-colors"
                        title="查看分段"
                      >
                        <Layers className="w-4 h-4" />
                      </button>
                      {/* 下载按钮 */}
                      <button
                        onClick={() => handleDownload(kb)}
                        className="p-2 text-slate-400 hover:text-primary-500 hover:bg-primary-50 dark:hover:bg-primary-900/30 rounded-lg transition-colors"
                        title="下载"
                      >
                        <Download className="w-4 h-4" />
                      </button>
                      {/* 重新向量化按钮（卡在 CHUNKED 可手动重试） */}
                      {kb.docStatus === 'VECTOR_STORED' && (
                        <button
                          onClick={() => setSplitModalKb(kb)}
                          className="p-2 text-slate-400 hover:text-primary-500 hover:bg-primary-50 dark:hover:bg-primary-900/30 rounded-lg transition-colors"
                          title="按策略重新切块"
                        >
                          <FileText className="w-4 h-4" />
                        </button>
                      )}
                      {isVectorStatusFailed(kb.docStatus) && (
                        <button
                          onClick={() => handleRevectorize(kb.id)}
                          disabled={revectorizing === kb.id}
                          className="p-2 text-slate-400 hover:text-primary-500 hover:bg-primary-50 dark:hover:bg-primary-900/30 rounded-lg transition-colors disabled:opacity-50"
                          title="重新向量化"
                        >
                          <LoadingButtonContent
                            loading={revectorizing === kb.id}
                            loadingText="重新向量化中"
                            iconOnly
                          >
                            <RefreshCw className="w-4 h-4" />
                          </LoadingButtonContent>
                        </button>
                      )}
                      {/* 删除按钮（仅文档所有者可删） */}
                      {kb.owned !== false && (
                      <button
                        onClick={() => setDeleteItem(kb)}
                        className="p-2 text-slate-400 hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-900/30 rounded-lg transition-colors"
                        title="删除"
                      >
                        <Trash2 className="w-4 h-4" />
                      </button>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {/* 删除确认对话框 */}
      <DeleteConfirmDialog
        open={deleteItem !== null}
        item={deleteItem}
        itemType="知识库"
        loading={deleting}
        onConfirm={handleDelete}
        onCancel={() => setDeleteItem(null)}
      />

      {/* 版本管理弹窗 */}
      {versionModalKb && (
          <div
            className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
            onClick={handleCloseVersions}
          >
            <div
              className="surface-card w-full max-w-2xl max-h-[80vh] flex flex-col"
              onClick={(e) => e.stopPropagation()}
            >
              <div className="flex items-center justify-between p-5 border-b border-slate-100 dark:border-slate-700">
                <div>
                  <h3 className="text-lg font-semibold text-slate-800 dark:text-white flex items-center gap-2">
                    <History className="w-5 h-5 text-primary-500" />
                    版本管理
                  </h3>
                  <p className="text-sm text-slate-500 dark:text-slate-400 mt-0.5">
                    {versionModalKb.name} · 共 {versions.length} 个版本
                  </p>
                </div>
                <button
                  onClick={handleCloseVersions}
                  className="p-1.5 text-slate-400 hover:text-slate-600 dark:hover:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-700 rounded-lg transition-colors"
                  aria-label="关闭版本管理"
                >
                  <X className="w-5 h-5" />
                </button>
              </div>

              <div className="px-5 pb-4 border-b border-slate-100 dark:border-slate-700">
                <p className="text-sm font-medium text-slate-700 dark:text-slate-200 mb-2">上传新版本</p>
                <div className="flex flex-col sm:flex-row gap-3">
                  <input
                    type="file"
                    accept=".pdf,.doc,.docx,.txt,.md,.csv,.xlsx,.xls"
                    onChange={(e) => {
                      setVersionUploadFile(e.target.files?.[0] ?? null);
                      setVersionUploadError('');
                    }}
                    className="block w-full text-sm text-slate-600 dark:text-slate-300 file:mr-3 file:py-2 file:px-3 file:rounded-lg file:border-0 file:bg-primary-50 file:text-primary-700"
                  />
                  <input
                    type="text"
                    value={versionChangelog}
                    onChange={(e) => setVersionChangelog(e.target.value)}
                    placeholder="变更说明（可选）"
                    className="flex-1 rounded-lg border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-900 px-3 py-2 text-sm"
                  />
                  <button
                    type="button"
                    onClick={handleUploadNewVersion}
                    disabled={!versionUploadFile || versionUploading}
                    className="shrink-0 px-4 py-2 text-sm bg-primary-500 text-white rounded-lg hover:bg-primary-600 disabled:opacity-40"
                  >
                    {versionUploading ? '上传中...' : '上传'}
                  </button>
                </div>
                <p className="text-xs text-slate-400 mt-2">
                  上传后会自动解析并切块，向量化在后台执行；可在列表查看处理状态。
                </p>
                {versionUploadError && (
                  <p className="mt-2 rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-700 dark:border-red-900/60 dark:bg-red-950/30 dark:text-red-300">
                    {versionUploadError}
                  </p>
                )}
              </div>

              <div className="flex-1 overflow-y-auto p-5">
                {versionsLoading ? (
                  <div className="text-center py-12 text-slate-400 dark:text-slate-500">
                    加载中...
                  </div>
                ) : versions.length === 0 ? (
                  <div className="text-center py-12 text-slate-400 dark:text-slate-500">
                    暂无版本记录
                  </div>
                ) : (
                  <ul className="space-y-3">
                    {versions.map((v) => {
                      const isCurrent = versionModalKb.currentVersionId === v.versionId;
                      return (
                        <li
                          key={v.versionId}
                          className={`flex items-start justify-between gap-4 p-4 rounded-lg border transition-colors ${
                            isCurrent
                              ? 'border-primary-300 dark:border-primary-600 bg-primary-50/50 dark:bg-primary-900/20'
                              : 'border-slate-100 dark:border-slate-700 hover:border-slate-200 dark:hover:border-slate-600'
                          }`}
                        >
                          <div className="flex-1 min-w-0">
                            <div className="flex items-center gap-2 flex-wrap">
                              <span className="font-medium text-slate-800 dark:text-white">
                                v{v.version}
                              </span>
                              <VectorStatusBadge
                                status={v.status}
                                textClassName="text-xs text-slate-600 dark:text-slate-300"
                              />
                              {isCurrent && (
                                <span className="px-1.5 py-0.5 bg-primary-500 text-white text-xs rounded">
                                  当前
                                </span>
                              )}
                            </div>
                            {v.changelog && (
                              <p className="text-sm text-slate-600 dark:text-slate-300 mt-1 break-words">
                                {v.changelog}
                              </p>
                            )}
                            <p className="text-xs text-slate-400 dark:text-slate-500 mt-1">
                              {formatDateTime(v.createdAt)}
                            </p>
                          </div>
                          <div className="flex flex-col gap-2 shrink-0">
                            <button
                              onClick={() => handleSwitchVersion(v.versionId)}
                              disabled={isCurrent || switchingVersionId !== null}
                              className="px-3 py-1.5 text-sm bg-primary-500 text-white rounded-lg hover:bg-primary-600 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                            >
                              {switchingVersionId === v.versionId
                                ? '切换中...'
                                : isCurrent
                                ? '当前版本'
                                : '切换'}
                            </button>
                            <button
                              onClick={() => handleActivateVersion(v.versionId)}
                              disabled={versionActionId !== null}
                              className="px-3 py-1.5 text-xs border border-emerald-300 text-emerald-700 dark:text-emerald-300 rounded-lg hover:bg-emerald-50 dark:hover:bg-emerald-900/20 disabled:opacity-40"
                            >
                              {versionActionId === v.versionId ? '处理中...' : '激活'}
                            </button>
                            <button
                              onClick={() => handleDeactivateVersion(v.versionId)}
                              disabled={versionActionId !== null || isCurrent}
                              className="px-3 py-1.5 text-xs border border-slate-300 text-slate-600 dark:text-slate-300 rounded-lg hover:bg-slate-50 dark:hover:bg-slate-700 disabled:opacity-40"
                            >
                              失效
                            </button>
                          </div>
                        </li>
                      );
                    })}
                  </ul>
                )}
              </div>
            </div>
          </div>
      )}

      {traceOpen && (
          <div
            className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
            onClick={() => setTraceOpen(false)}
          >
            <div
              className="surface-card w-full max-w-4xl max-h-[80vh] flex flex-col"
              onClick={(e) => e.stopPropagation()}
            >
              <div className="flex items-center justify-between p-5 border-b border-slate-100 dark:border-slate-700">
                <h3 className="text-lg font-semibold text-slate-800 dark:text-white flex items-center gap-2">
                  <Eye className="w-5 h-5 text-primary-500" />
                检索记录
                </h3>
                <button
                  onClick={() => setTraceOpen(false)}
                  className="p-1.5 text-slate-400 hover:text-slate-600 dark:hover:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-700 rounded-lg transition-colors"
                  aria-label="关闭检索记录"
                >
                  <X className="w-5 h-5" />
                </button>
              </div>
              <div className="flex-1 overflow-y-auto p-5 space-y-3">
                {tracesLoading ? (
                  <div className="py-12 text-center text-slate-400">加载中...</div>
                ) : traces.length === 0 ? (
                  <div className="py-12 text-center text-slate-400">暂无检索记录</div>
                ) : traces.map((trace) => {
                  const activeTrace = expandedTraceId === trace.traceId && traceDetail ? traceDetail : trace;
                  const retrieved = parseTraceList(activeTrace.retrievedJson).slice(0, 5);
                  const reranked = parseTraceList(activeTrace.rerankedJson).slice(0, 5);
                  const sources = parseSourceList(activeTrace.finalSourcesJson).slice(0, 3);
                  return (
                    <div
                      key={trace.traceId}
                      className="p-4 rounded-lg border border-slate-100 dark:border-slate-700 cursor-pointer hover:border-primary-200 dark:hover:border-primary-700 transition-colors"
                      onClick={() => void handleExpandTrace(trace.traceId)}
                    >
                      <div className="flex items-center justify-between gap-3">
                        <p className="font-medium text-slate-800 dark:text-white truncate">{trace.question}</p>
                        <span className="text-xs text-slate-400 shrink-0">
                          {expandedTraceId === trace.traceId ? '收起' : '详情'}
                        </span>
                      </div>
                      <div className="mt-2 grid grid-cols-2 gap-2 text-xs text-slate-500 dark:text-slate-400">
                        <span>耗时：{trace.latencyMs ?? '-'}ms</span>
                        <span>置信度：{trace.confidence ?? '-'}</span>
                        {activeTrace.evidenceStatus && (
                          <span>证据状态：{activeTrace.evidenceStatus}</span>
                        )}
                        {(activeTrace.cragGrade || activeTrace.cragAction) && (
                          <span>
                            CRAG：{activeTrace.cragGrade ?? '-'}
                            {activeTrace.cragAction ? ` / ${activeTrace.cragAction}` : ''}
                          </span>
                        )}
                      </div>
                      {activeTrace.rewrittenQuestion && (
                        <p className="mt-2 text-sm text-slate-600 dark:text-slate-300">
                          改写：{activeTrace.rewrittenQuestion}
                        </p>
                      )}
                      {parseJsonList(activeTrace.decomposedQueriesJson).length > 0 && (
                        <p className="mt-1 text-xs text-slate-500 dark:text-slate-400">
                          子查询：{parseJsonList(activeTrace.decomposedQueriesJson).join(' · ')}
                        </p>
                      )}
                      {parseJsonList(activeTrace.degradedReasonsJson).length > 0 && (
                        <p className="mt-1 text-xs text-amber-600 dark:text-amber-400">
                          降级：{parseJsonList(activeTrace.degradedReasonsJson).join(' · ')}
                        </p>
                      )}
                      {parseJsonList(activeTrace.invalidCitationsJson).length > 0 && (
                        <p className="mt-1 text-xs text-amber-600 dark:text-amber-400">
                          无效引用编号：[{parseJsonList(activeTrace.invalidCitationsJson).join(', ')}]
                        </p>
                      )}
                      <div className="mt-3 grid gap-3 lg:grid-cols-3">
                        {([
                          ['初步检索', retrieved, 'score'],
                          ['重排结果', reranked, 'rerankScore'],
                        ] as Array<[string, TraceContent[], 'score' | 'rerankScore']>).map(([title, items, scoreKey]) => (
                          <div key={title} className="rounded-lg bg-slate-50 dark:bg-slate-700/60 p-3">
                            <p className="text-xs font-semibold text-slate-600 dark:text-slate-300 mb-2">{title}</p>
                            {items.length === 0 ? (
                              <p className="text-xs text-slate-400">无记录</p>
                            ) : items.map(item => (
                              <div key={`${title}-${item.rank}-${item.chunkId}`} className="mb-2 last:mb-0">
                                <p className="text-xs text-slate-500 dark:text-slate-400">
                                  #{item.rank} chunk={item.chunkId || '-'} {scoreKey === 'score' ? 'score' : 'rerank'}={
                                    scoreKey === 'score' ? item.score ?? '-' : item.rerankScore ?? '-'
                                  }
                                </p>
                                <p className="text-xs text-slate-700 dark:text-slate-200 line-clamp-2">{item.snippet}</p>
                              </div>
                            ))}
                          </div>
                        ))}
                        <div className="rounded-lg bg-slate-50 dark:bg-slate-700/60 p-3">
                          <p className="text-xs font-semibold text-slate-600 dark:text-slate-300 mb-2">最终引用</p>
                          {sources.length === 0 ? (
                            <p className="text-xs text-slate-400">无引用</p>
                          ) : sources.map((source, index) => (
                            <div key={`${trace.traceId}-source-${index}`} className="mb-2 last:mb-0">
                              <p className="text-xs text-slate-500 dark:text-slate-400">
                                [{index + 1}] {source.documentTitle || '-'} similarity={source.similarity ?? '-'}
                              </p>
                              <p className="text-xs text-slate-700 dark:text-slate-200 line-clamp-2">{source.snippet || ''}</p>
                            </div>
                          ))}
                        </div>
                      </div>
                      {expandedTraceId === trace.traceId && (
                        <div className="mt-3 pt-3 border-t border-slate-100 dark:border-slate-700">
                          {traceDetailLoading ? (
                            <p className="text-xs text-slate-400">加载完整记录...</p>
                          ) : activeTrace.answer ? (
                            <>
                              <p className="text-xs font-semibold text-slate-600 dark:text-slate-300 mb-1">完整回答</p>
                              <p className="text-sm text-slate-700 dark:text-slate-200 whitespace-pre-wrap">{activeTrace.answer}</p>
                            </>
                          ) : (
                            <p className="text-xs text-slate-400">无回答记录</p>
                          )}
                          <p className="text-xs text-slate-400 mt-2">{formatDateTime(trace.createdAt)}</p>
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            </div>
          </div>
      )}

      {segmentModalKb && (
          <div
            className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
            onClick={() => setSegmentModalKb(null)}
          >
            <div
              className="surface-card w-full max-w-4xl max-h-[80vh] flex flex-col"
              onClick={(e) => e.stopPropagation()}
            >
              <div className="flex items-center justify-between p-5 border-b border-slate-100 dark:border-slate-700">
                <div>
                  <h3 className="text-lg font-semibold text-slate-800 dark:text-white flex items-center gap-2">
                    <Layers className="w-5 h-5 text-primary-500" />
                    分段预览
                  </h3>
                  <p className="text-sm text-slate-500 dark:text-slate-400 mt-0.5">
                    {segmentModalKb.name} · 共 {segmentTotal} 段
                  </p>
                </div>
                <button
                  onClick={() => setSegmentModalKb(null)}
                  className="p-1.5 text-slate-400 hover:text-slate-600 dark:hover:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-700 rounded-lg"
                  aria-label="关闭分段预览"
                >
                  <X className="w-5 h-5" />
                </button>
              </div>
              <div className="flex-1 overflow-y-auto p-5 space-y-3">
                {segmentsLoading ? (
                  <div className="py-12 text-center text-slate-400">加载中...</div>
                ) : segments.length === 0 ? (
                  <div className="py-12 text-center text-slate-400">暂无分段</div>
                ) : segments.map((seg) => (
                  <div key={seg.id} className="rounded-lg border border-slate-100 dark:border-slate-700 p-3">
                    <div className="mb-2 flex flex-wrap items-center gap-2 text-xs">
                      <span className="font-medium text-slate-500 dark:text-slate-400">
                        第 {seg.chunkOrder + 1} 段
                      </span>
                      <span className={`rounded-full px-2 py-0.5 ${
                        seg.skipEmbedding === 1
                          ? 'bg-amber-50 text-amber-800 dark:bg-amber-950/40 dark:text-amber-200'
                          : 'bg-slate-100 text-slate-600 dark:bg-slate-700 dark:text-slate-300'
                      }`}>
                        {segmentKindLabel(seg)}
                      </span>
                      {seg.textLength != null && (
                        <span className="text-slate-400 dark:text-slate-500">{seg.textLength} 字</span>
                      )}
                      {seg.brotherChunkId && (
                        <span
                          className="rounded-full bg-primary-50 px-2 py-0.5 text-primary-700 dark:bg-primary-950/40 dark:text-primary-300"
                          title={`兄弟组：${seg.brotherChunkId}`}
                        >
                          兄弟组 · 第 {seg.brotherChunkIndex ?? '?'} 段
                        </span>
                      )}
                    </div>
                    {(seg.parentChunkId || seg.brotherChunkId) && (
                      <p className="mb-2 text-[11px] text-slate-400 dark:text-slate-500">
                        {seg.parentChunkId && `父块 ${seg.parentChunkId.slice(0, 12)}…`}
                        {seg.parentChunkId && seg.brotherChunkId && ' · '}
                        {seg.brotherChunkId && `兄弟组 ${seg.brotherChunkId.slice(0, 12)}…`}
                      </p>
                    )}
                    <p className="text-sm text-slate-700 dark:text-slate-200 whitespace-pre-wrap">{seg.textPreview}</p>
                  </div>
                ))}
              </div>
              {segmentTotal > 20 && (
                <div className="flex items-center justify-between p-4 border-t border-slate-100 dark:border-slate-700">
                  <button
                    disabled={segmentPage <= 1 || segmentsLoading}
                    onClick={() => segmentModalKb && void handleShowSegments(segmentModalKb, segmentPage - 1)}
                    className="px-3 py-1.5 text-sm rounded-lg border border-slate-200 dark:border-slate-600 disabled:opacity-40"
                  >
                    上一页
                  </button>
                  <span className="text-sm text-slate-500">第 {segmentPage} 页</span>
                  <button
                    disabled={segmentPage * 20 >= segmentTotal || segmentsLoading}
                    onClick={() => segmentModalKb && void handleShowSegments(segmentModalKb, segmentPage + 1)}
                    className="px-3 py-1.5 text-sm rounded-lg border border-slate-200 dark:border-slate-600 disabled:opacity-40"
                  >
                    下一页
                  </button>
                </div>
              )}
            </div>
          </div>
      )}

      {splitModalKb && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
          <div className="surface-card w-full max-w-md p-5">
            <h3 className="text-lg font-semibold text-slate-800 dark:text-white mb-1">重新切块</h3>
            <p className="text-sm text-slate-500 mb-4">{splitModalKb.name}</p>
            <label className="block text-sm text-slate-600 dark:text-slate-300 mb-1">策略</label>
            <select
              value={splitType}
              onChange={e => setSplitType(e.target.value)}
              className="w-full mb-3 rounded-lg border border-slate-200 dark:border-slate-600 px-3 py-2 text-sm bg-white dark:bg-slate-700"
            >
              <option value="TITLE">TITLE（按标题切分，默认）</option>
              <option value="PARENT_CHILD">PARENT_CHILD（TITLE 别名）</option>
              <option value="BROTHER">BROTHER（兄弟分段 + 层级关系）</option>
              <option value="SMART">SMART（按 1～6 级标题 + 10% overlap）</option>
              <option value="LENGTH">LENGTH（按长度）</option>
            </select>
            <p className="mb-4 text-xs leading-5 text-slate-400">
              标准父子：一节/一题≤分段长度则直接入库（检索段）；超长才留父块（不入库）并按约 40% 长度切子块。空的「## 基础」只写入后续题目。
            </p>
            <div className="grid grid-cols-3 gap-3 mb-4">
              <div>
                <label className="block text-xs text-slate-500 mb-1">分段长度</label>
                <input
                  type="number"
                  value={splitChunkSize}
                  onChange={e => setSplitChunkSize(Number(e.target.value))}
                  className="w-full rounded-lg border border-slate-200 dark:border-slate-600 px-3 py-2 text-sm"
                />
              </div>
              <div>
                <label className="block text-xs text-slate-500 mb-1">重叠长度</label>
                <input
                  type="number"
                  value={splitOverlap}
                  onChange={e => setSplitOverlap(Number(e.target.value))}
                  className="w-full rounded-lg border border-slate-200 dark:border-slate-600 px-3 py-2 text-sm"
                />
              </div>
              <div>
                <label className="block text-xs text-slate-500 mb-1">标题级数</label>
                <input
                  type="number"
                  min={1}
                  max={6}
                  value={splitTitleLevel}
                  onChange={e => setSplitTitleLevel(Number(e.target.value))}
                  className="w-full rounded-lg border border-slate-200 dark:border-slate-600 px-3 py-2 text-sm"
                />
              </div>
            </div>
            <div className="flex justify-end gap-2">
              <button
                type="button"
                onClick={() => setSplitModalKb(null)}
                className="px-4 py-2 text-sm rounded-lg border border-slate-200 dark:border-slate-600"
              >
                取消
              </button>
              <button
                type="button"
                disabled={splitting}
                onClick={() => void handleSplitDocument()}
                className="px-4 py-2 text-sm rounded-lg bg-primary-500 text-white disabled:opacity-50"
              >
                {splitting ? '切块中...' : '开始切块'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
