import {useCallback, useEffect, useMemo, useRef, useState, useTransition} from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import {Virtuoso, type VirtuosoHandle} from 'react-virtuoso';
import {
  knowledgeBaseApi,
  type KnowledgeBaseItem,
  type RagEvalResponse,
  type RagQaExportResponse,
  type SortOption
} from '../api/knowledgebase';
import {ragChatApi, type RagCardChoice, type RagChatSessionListItem, type RagSourceDTO} from '../api/ragChat';
import {ragModuleApi} from '../api/ragModule';
import {getErrorMessage} from '../api/request';
import {formatTimeAgo} from '../utils/date';
import {formatFileSize} from '../utils/format';
import DeleteConfirmDialog from '../components/DeleteConfirmDialog';
import KnowledgeBaseSortSelect from '../components/KnowledgeBaseSortSelect';
import CodeBlock from '../components/CodeBlock';
import {EmptyState, LoadingState} from '../components/PageState';
import PageHeader from '../components/ui/PageHeader';
import {BarChart3, Bug, ChevronLeft, ChevronRight, Edit, MessageSquare, Pin, Plus, Trash2, X,} from 'lucide-react';
import { useSearchParams } from 'react-router-dom';

interface KnowledgeBaseQueryPageProps {
  onBack: () => void;
  onUpload: () => void;
}

interface Message {
  id?: number;
  type: 'user' | 'assistant';
  content: string;
  timestamp: Date;
}

interface CategoryGroup {
  name: string;
  items: KnowledgeBaseItem[];
  isExpanded: boolean;
}

export function buildRagCardFollowUp(choice: RagCardChoice): string {
  switch (choice.type) {
    case 'schedule':
      return `请查询「${choice.label}」的面试安排`;
    case 'session':
      return `请总结这场面试：「${choice.label}」`;
    case 'jobTrack':
    case 'skill':
      return `请针对「${choice.label}」方向给出面试准备建议`;
    default:
      return `请分析简历「${choice.label}」`;
  }
}

export function removeQuestionSearchParam(searchParams: URLSearchParams): URLSearchParams {
  const next = new URLSearchParams(searchParams);
  next.delete('question');
  return next;
}

export function citationStatusLabel(source: RagSourceDTO, finalized: boolean): string | null {
  if (!finalized) return null;
  return source.cited ? '已引用' : '未引用';
}

export default function KnowledgeBaseQueryPage({ onBack, onUpload }: KnowledgeBaseQueryPageProps) {
  const [searchParams, setSearchParams] = useSearchParams();
  // 知识库状态
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBaseItem[]>([]);
  const [selectedKbIds, setSelectedKbIds] = useState<Set<number>>(new Set());
  const [loadingList, setLoadingList] = useState(true);

  // 搜索和排序状态
  const [searchKeyword, setSearchKeyword] = useState('');
  const [sortBy, setSortBy] = useState<SortOption>('time');
  const [expandedCategories, setExpandedCategories] = useState<Set<string>>(new Set(['未分类']));

  // 右侧面板状态
  const [rightPanelOpen, setRightPanelOpen] = useState(true);

  // 会话状态
  const [sessions, setSessions] = useState<RagChatSessionListItem[]>([]);
  const [currentSessionId, setCurrentSessionId] = useState<number | null>(null);
  const [currentSessionTitle, setCurrentSessionTitle] = useState<string>('');
  const [loadingSessions, setLoadingSessions] = useState(false);
  const [sessionDeleteConfirm, setSessionDeleteConfirm] = useState<{ id: number; title: string } | null>(null);
  const [editingSessionTitle, setEditingSessionTitle] = useState<{ id: number; title: string } | null>(null);
  const [newSessionTitle, setNewSessionTitle] = useState('');

  // 消息状态
  const [question, setQuestion] = useState(() => searchParams.get('question')?.slice(0, 2000) ?? '');
  const [messages, setMessages] = useState<Message[]>([]);
  const [loading, setLoading] = useState(false);
  // 当前流式回答的阶段进度（来自 progress: 前缀事件），显示在助手气泡上方
  const [progressText, setProgressText] = useState('');
  const [rewrittenQuestion, setRewrittenQuestion] = useState('');
  // 当前流式回答的引用来源（来自 reference: 前缀事件），显示在助手气泡下方
  const [activeSources, setActiveSources] = useState<RagSourceDTO[] | null>(null);
  const [citationFinalized, setCitationFinalized] = useState(false);
  const [cardMessage, setCardMessage] = useState('');
  const [cardChoices, setCardChoices] = useState<RagCardChoice[]>([]);
  const [evalOpen, setEvalOpen] = useState(false);
  const [evalInput, setEvalInput] = useState('');
  const [evalResult, setEvalResult] = useState<RagEvalResponse | null>(null);
  const [evalLoading, setEvalLoading] = useState(false);
  const [evalError, setEvalError] = useState('');
  const [exportLoading, setExportLoading] = useState(false);
  const [exportResult, setExportResult] = useState<RagQaExportResponse | null>(null);
  const [datasetQuestion, setDatasetQuestion] = useState('');
  const [datasetResult, setDatasetResult] = useState<{ question: string; answer: string; sources: unknown[] } | null>(null);
  const [datasetLoading, setDatasetLoading] = useState(false);
  const [sessionBoundKbIds, setSessionBoundKbIds] = useState<Set<number>>(new Set());
  const [syncingKb, setSyncingKb] = useState(false);
  const [debugOpen, setDebugOpen] = useState(false);
  const [debugQuestion, setDebugQuestion] = useState('');
  const [debugOutput, setDebugOutput] = useState('');
  const [debugLoading, setDebugLoading] = useState(false);

  // refs
  const virtuosoRef = useRef<VirtuosoHandle>(null);
  const rafRef = useRef<number>();

  const [, startTransition] = useTransition();

  const loadKnowledgeBases = useCallback(async () => {
    setLoadingList(true);
    try {
      // 问答助手只显示向量化完成的知识库
      const list = await knowledgeBaseApi.getAllKnowledgeBases(sortBy, 'VECTOR_STORED');
      setKnowledgeBases(list);
    } catch (err) {
      console.error('加载知识库列表失败', err);
    } finally {
      setLoadingList(false);
    }
  }, [sortBy]);

  const handleSearch = async () => {
    if (!searchKeyword.trim()) {
      loadKnowledgeBases();
      return;
    }
    setLoadingList(true);
    try {
      const list = await knowledgeBaseApi.search(searchKeyword.trim());
      setKnowledgeBases(list);
    } catch (err) {
      console.error('搜索知识库失败', err);
    } finally {
      setLoadingList(false);
    }
  };

  const groupedKnowledgeBases = useMemo((): CategoryGroup[] => {
    const groups: Map<string, KnowledgeBaseItem[]> = new Map();

    knowledgeBases.forEach(kb => {
      const category = kb.category || '未分类';
      const items = groups.get(category) ?? [];
      items.push(kb);
      groups.set(category, items);
    });

    const result: CategoryGroup[] = [];
    const sortedCategories = Array.from(groups.keys()).sort((a, b) => {
      if (a === '未分类') return 1;
      if (b === '未分类') return -1;
      return a.localeCompare(b);
    });

    sortedCategories.forEach(name => {
      const items = groups.get(name) ?? [];
      result.push({
        name,
        items,
        isExpanded: expandedCategories.has(name),
      });
    });

    return result;
  }, [knowledgeBases, expandedCategories]);

  const toggleCategory = (category: string) => {
    setExpandedCategories(prev => {
      const next = new Set(prev);
      if (next.has(category)) {
        next.delete(category);
      } else {
        next.add(category);
      }
      return next;
    });
  };

  const loadSessions = useCallback(async () => {
    setLoadingSessions(true);
    try {
      const list = await ragChatApi.listSessions();
      setSessions(list);
    } catch (err) {
      console.error('加载会话列表失败', err);
    } finally {
      setLoadingSessions(false);
    }
  }, []);

  useEffect(() => {
    loadSessions();
  }, [loadSessions]);

  useEffect(() => {
    if (!searchKeyword) {
      loadKnowledgeBases();
    }
  }, [loadKnowledgeBases, searchKeyword]);

  const handleToggleKb = (kbId: number) => {
    setSelectedKbIds(prev => {
      const newSet = new Set(prev);
      if (newSet.has(kbId)) {
        newSet.delete(kbId);
      } else {
        newSet.add(kbId);
      }
      return newSet;
    });
  };

  const kbNeedsSync = useMemo(() => {
    if (!currentSessionId) return false;
    if (selectedKbIds.size !== sessionBoundKbIds.size) return true;
    return Array.from(selectedKbIds).some((id) => !sessionBoundKbIds.has(id));
  }, [currentSessionId, selectedKbIds, sessionBoundKbIds]);

  const handleSyncKnowledgeBases = async () => {
    if (!currentSessionId || selectedKbIds.size === 0) return;
    setSyncingKb(true);
    try {
      await ragChatApi.updateKnowledgeBases(currentSessionId, Array.from(selectedKbIds));
      setSessionBoundKbIds(new Set(selectedKbIds));
    } catch (err) {
      console.error('同步知识库失败', err);
    } finally {
      setSyncingKb(false);
    }
  };

  const handleNewSession = () => {
    if (loading) return;
    setCurrentSessionId(null);
    setCurrentSessionTitle('');
    setMessages([]);
    setSessionBoundKbIds(new Set());
    setActiveSources(null);
    setCitationFinalized(false);
  };

  const handleLoadSession = async (sessionId: number) => {
    if (loading) return;
    try {
      const detail = await ragChatApi.getSessionDetail(sessionId);
      setCurrentSessionId(detail.id);
      setCurrentSessionTitle(detail.title);
      const kbIds = new Set(detail.knowledgeBases.map(kb => kb.id));
      setSelectedKbIds(kbIds);
      setSessionBoundKbIds(kbIds);
      setActiveSources(null);
      setCitationFinalized(false);
      setMessages(detail.messages.map(m => ({
        id: m.id,
        type: m.type,
        content: m.content,
        timestamp: new Date(m.createdAt),
      })));
    } catch (err) {
      console.error('加载会话失败', err);
    }
  };

  const handleDeleteSession = async () => {
    if (!sessionDeleteConfirm || loading) return;
    try {
      await ragChatApi.deleteSession(sessionDeleteConfirm.id);
      await loadSessions();
      if (currentSessionId === sessionDeleteConfirm.id) {
        handleNewSession();
      }
      setSessionDeleteConfirm(null);
    } catch (err) {
      console.error('删除会话失败', err);
    }
  };

  const handleEditSessionTitle = (sessionId: number, currentTitle: string) => {
    if (loading) return;
    setEditingSessionTitle({ id: sessionId, title: currentTitle });
    setNewSessionTitle(currentTitle);
  };

  const handleSaveSessionTitle = async () => {
    if (!editingSessionTitle || !newSessionTitle.trim()) return;
    try {
      await ragChatApi.updateSessionTitle(editingSessionTitle.id, newSessionTitle.trim());
      await loadSessions();
      if (currentSessionId === editingSessionTitle.id) {
        setCurrentSessionTitle(newSessionTitle.trim());
      }
      setEditingSessionTitle(null);
      setNewSessionTitle('');
    } catch (err) {
      console.error('更新会话标题失败', err);
    }
  };

  const handleTogglePin = async (sessionId: number, e: React.MouseEvent) => {
    e.stopPropagation();
    if (loading) return;
    try {
      await ragChatApi.togglePin(sessionId);
      await loadSessions();
    } catch (err) {
      console.error('切换置顶状态失败', err);
    }
  };

  const formatMarkdown = (text: string): string => {
    if (!text) return '';
    return text
      // 处理转义换行符
      .replace(/\\n/g, '\n')
      // 确保标题 # 后有空格
      .replace(/^(#{1,6})([^\s#\n])/gm, '$1 $2')
      // 确保有序列表数字后有空格（如 1.xxx -> 1. xxx）
      .replace(/^(\s*)(\d+)\.([^\s\n])/gm, '$1$2. $3')
      // 确保无序列表 - 或 * 后有空格
      .replace(/^(\s*[-*])([^\s\n-])/gm, '$1 $2')
      // 压缩多余空行
      .replace(/\n{3,}/g, '\n\n');
  };

  const handleSubmitQuestion = async () => {
    if (!question.trim() || selectedKbIds.size === 0 || loading) return;

    const userQuestion = question.trim();
    setQuestion('');
    setLoading(true);
    setProgressText('');
    setRewrittenQuestion('');
    setActiveSources(null);
    setCitationFinalized(false);
    setCardMessage('');
    setCardChoices([]);

    let sessionId = currentSessionId;
    if (!sessionId) {
      try {
        const session = await ragChatApi.createSession(Array.from(selectedKbIds));
        sessionId = session.id;
        setCurrentSessionId(session.id);
        setCurrentSessionTitle(session.title);
        setSessionBoundKbIds(new Set(selectedKbIds));
      } catch (err) {
        console.error('创建会话失败', err);
        setLoading(false);
        return;
      }
    }

    if (searchParams.has('question')) {
      setSearchParams(removeQuestionSearchParam(searchParams), { replace: true });
    }

    const userMessage: Message = {
      type: 'user',
      content: userQuestion,
      timestamp: new Date(),
    };
    setMessages(prev => [...prev, userMessage]);

    const assistantMessage: Message = {
      type: 'assistant',
      content: '',
      timestamp: new Date(),
    };
    setMessages(prev => [...prev, assistantMessage]);

    let fullContent = '';
    const updateAssistantMessage = (content: string) => {
      setMessages(prev => {
        const newMessages = [...prev];
        const lastIndex = newMessages.length - 1;
        if (lastIndex >= 0 && newMessages[lastIndex].type === 'assistant') {
          newMessages[lastIndex] = {
            ...newMessages[lastIndex],
            content: content,
          };
        }
        return newMessages;
      });
    };

    let cardContent = '';
    try {
      await ragChatApi.sendMessageStream(
        sessionId,
        userQuestion,
        (chunk: string) => {
          fullContent += chunk;
          if (rafRef.current) {
            cancelAnimationFrame(rafRef.current);
          }
          rafRef.current = requestAnimationFrame(() => {
            startTransition(() => {
              updateAssistantMessage(fullContent);
            });
          });
        },
        () => {
          if (!fullContent.trim()) {
            updateAssistantMessage(cardContent || '本次回答未生成有效内容，请重新提问。');
          }
          setLoading(false);
          setProgressText('');
          setRewrittenQuestion('');
          loadSessions();
        },
        (error: Error) => {
          console.error('流式查询失败:', error);
          updateAssistantMessage(fullContent || getErrorMessage(error, '回答失败，请重试'));
          setLoading(false);
          setProgressText('');
          setRewrittenQuestion('');
          setActiveSources(null);
          setCitationFinalized(false);
        },
        (text: string) => {
          setProgressText(text);
        },
        (sources: RagSourceDTO[]) => {
          setActiveSources(sources);
        },
        (metadata) => {
          setActiveSources(metadata.sources);
          setCitationFinalized(true);
        },
        (text: string) => {
          cardContent = text;
          setCardMessage(text);
        },
        (choices: RagCardChoice[]) => {
          setCardChoices(choices);
        },
        (text: string) => {
          setRewrittenQuestion(text);
        }
      );
    } catch (err) {
      console.error('发起流式查询失败:', err);
      updateAssistantMessage(getErrorMessage(err, '回答失败，请重试'));
      setLoading(false);
      setActiveSources(null);
      setCitationFinalized(false);
    }
  };

  const handleRunEval = async () => {
    if (selectedKbIds.size === 0 || !evalInput.trim()) return;
    setEvalLoading(true);
    setEvalError('');
    try {
      const parsed = JSON.parse(evalInput);
      const items = Array.isArray(parsed) ? parsed : parsed.items;
      if (!Array.isArray(items) || items.length === 0) {
        throw new Error('请输入至少一条评测用例');
      }
      const k = Array.isArray(parsed) ? 5 : parsed.k;
      const title = Array.isArray(parsed) ? '手动检索评测' : parsed.title;
      setEvalResult(await knowledgeBaseApi.evaluateRetrieval({
        knowledgeBaseIds: Array.from(selectedKbIds),
        items,
        k,
        title,
      }));
    } catch (err) {
      console.error('RAG 评测失败:', err);
      setEvalError(getErrorMessage(err, '评测 JSON 格式不正确'));
      setEvalResult(null);
    } finally {
      setEvalLoading(false);
    }
  };

  const handleExportQa = async () => {
    if (selectedKbIds.size === 0 || !evalInput.trim()) return;
    setExportLoading(true);
    setEvalError('');
    try {
      const parsed = JSON.parse(evalInput);
      const items = Array.isArray(parsed) ? parsed : parsed.items;
      if (!Array.isArray(items) || items.length === 0) {
        throw new Error('请输入至少一条评测用例');
      }
      const exportItems = items.map((item: { question?: string; groundTruth?: string; expectedKeywords?: string[] }) => ({
        question: item.question || '',
        groundTruth: item.groundTruth || (item.expectedKeywords || []).join(', '),
      }));
      setExportResult(await knowledgeBaseApi.exportQa(Array.from(selectedKbIds), exportItems));
    } catch (err) {
      console.error('RAGAS 导出失败:', err);
      setEvalError(getErrorMessage(err, '导出失败，请检查 JSON 格式'));
      setExportResult(null);
    } finally {
      setExportLoading(false);
    }
  };

  const handleGenerateDataset = async () => {
    if (selectedKbIds.size === 0 || !datasetQuestion.trim()) return;
    setDatasetLoading(true);
    try {
      setDatasetResult(await knowledgeBaseApi.generateDataset(datasetQuestion.trim(), Array.from(selectedKbIds)));
    } catch (err) {
      console.error('生成样本失败:', err);
      setDatasetResult(null);
    } finally {
      setDatasetLoading(false);
    }
  };

  const handleRunDebug = async (mode: 'intent' | 'prompt' | 'rewrite' | 'rerank') => {
    if (!debugQuestion.trim()) return;
    setDebugLoading(true);
    setDebugOutput('');
    try {
      if (mode === 'intent') {
        const result = await ragModuleApi.testIntent(debugQuestion.trim());
        setDebugOutput(JSON.stringify(result, null, 2));
      } else if (mode === 'prompt') {
        setDebugOutput(await ragModuleApi.testPrompt(debugQuestion.trim()));
      } else if (mode === 'rewrite') {
        const result = await ragModuleApi.testRewrite(debugQuestion.trim());
        setDebugOutput(result.join('\n---\n'));
      } else {
        setDebugOutput(await ragModuleApi.testRerank(debugQuestion.trim()));
      }
    } catch (err) {
      setDebugOutput(getErrorMessage(err, '调试请求失败'));
    } finally {
      setDebugLoading(false);
    }
  };

  return (
    <div className="max-w-7xl mx-auto pb-6">
      <PageHeader
        eyebrow="知识库"
        title="知识问答"
        description="选择资料后提问，回答会标注引用来源。"
        action={(
          <div className="flex flex-wrap gap-2">
            <button onClick={onUpload} className="btn-secondary px-3 py-2 text-sm">上传资料</button>
            <button
              onClick={() => setDebugOpen((v) => !v)}
              title="查看意图识别、问题改写和重排结果"
              className="btn-secondary inline-flex items-center gap-1.5 px-3 py-2 text-sm"
            >
              <Bug className="w-4 h-4" />
              链路调试
            </button>
            <button
              onClick={() => setEvalOpen(true)}
              disabled={selectedKbIds.size === 0}
              title="先在右侧选择需要评测的资料"
              className="btn-secondary inline-flex items-center gap-1.5 px-3 py-2 text-sm disabled:opacity-50"
            >
              <BarChart3 className="w-4 h-4" />
              检索评测
            </button>
            <button onClick={onBack} className="btn-secondary px-3 py-2 text-sm">返回</button>
          </div>
        )}
      />

      <div className={`grid gap-4 lg:h-[calc(100vh-10rem)] ${rightPanelOpen
        ? 'lg:grid-cols-[16rem_minmax(0,1fr)_17.5rem]'
        : 'lg:grid-cols-[16rem_minmax(0,1fr)_2.5rem]'}`}>
        {/* 左侧：对话历史 */}
        <div className="min-h-0 w-full lg:h-full">
          <div
              className="surface-card flex max-h-72 flex-col p-3 lg:h-full lg:max-h-none">
            <div className="flex items-center justify-between mb-4">
              <h2 className="text-base font-semibold text-slate-800 dark:text-white">对话历史</h2>
              <button
                onClick={handleNewSession}
                disabled={selectedKbIds.size === 0 || loading}
                className="p-1.5 text-primary-500 hover:bg-primary-50 dark:hover:bg-primary-900/30 rounded-lg transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                title="新建对话"
              >
                <Plus className="w-5 h-5" />
              </button>
            </div>

            <div className="flex-1 overflow-y-auto">
              {loadingSessions ? (
                <LoadingState compact />
              ) : sessions.length === 0 ? (
                <EmptyState
                  className="text-center py-6 text-slate-400 dark:text-slate-500"
                  title="暂无对话历史"
                  titleClassName="text-sm"
                />
              ) : (
                <div className="space-y-2">
                  {sessions.map((session) => (
                    <div
                      key={session.id}
                      onClick={() => handleLoadSession(session.id)}
                      aria-disabled={loading}
                      className={`group rounded-lg p-3 transition-colors focus-within:ring-2 focus-within:ring-primary-500 ${loading ? 'cursor-not-allowed opacity-60' : 'cursor-pointer'} ${currentSessionId === session.id
                          ? 'bg-primary-50 dark:bg-primary-900/30 border border-primary-500'
                          : 'bg-slate-50 dark:bg-slate-700/50 hover:bg-slate-100 dark:hover:bg-slate-700 border border-transparent'
                        } ${session.isPinned ? 'border-l-4 border-l-primary-500' : ''}`}
                    >
                      <div className="flex items-start justify-between gap-2">
                        <button
                          type="button"
                          onClick={(event) => {
                            event.stopPropagation();
                            void handleLoadSession(session.id);
                          }}
                          disabled={loading}
                          className="min-w-0 flex-1 text-left focus-visible:outline-none disabled:cursor-not-allowed"
                        >
                          <div className="flex items-center gap-1.5">
                            {session.isPinned && (
                              <Pin className="w-3.5 h-3.5 text-primary-500 fill-primary-500 flex-shrink-0" />
                            )}
                            <p className="font-medium text-slate-800 dark:text-white text-sm truncate">{session.title}</p>
                          </div>
                          <p className="text-xs text-slate-500 dark:text-slate-400 mt-1">
                            {session.messageCount} 条消息 · {formatTimeAgo(session.updatedAt)}
                          </p>
                        </button>
                        <div className="flex items-center gap-1 opacity-70 transition-opacity group-hover:opacity-100 group-focus-within:opacity-100 lg:opacity-0">
                          <button
                            onClick={(e) => handleTogglePin(session.id, e)}
                            disabled={loading}
                            className={`p-1 rounded transition-colors ${session.isPinned
                              ? 'text-primary-500 hover:text-primary-600'
                              : 'text-slate-400 hover:text-primary-500'
                              }`}
                            title={session.isPinned ? '取消置顶' : '置顶'}
                          >
                            <Pin className={`w-4 h-4 ${session.isPinned ? 'fill-primary-500' : ''}`} />
                          </button>
                          <button
                            onClick={(e) => {
                              e.stopPropagation();
                              handleEditSessionTitle(session.id, session.title);
                            }}
                            disabled={loading}
                            className="p-1 text-slate-400 hover:text-primary-500 rounded transition-colors disabled:cursor-not-allowed"
                            title="编辑标题"
                          >
                            <Edit className="w-4 h-4" />
                          </button>
                          <button
                            onClick={(e) => {
                              e.stopPropagation();
                              setSessionDeleteConfirm({ id: session.id, title: session.title });
                            }}
                            disabled={loading}
                            className="p-1 text-slate-400 hover:text-red-500 rounded transition-colors disabled:cursor-not-allowed"
                            title="删除"
                          >
                            <Trash2 className="w-4 h-4" />
                          </button>
                        </div>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>
        </div>

        {/* 中间：聊天区域 */}
        <div className="min-h-[34rem] min-w-0 lg:h-full">
          <div
              className="surface-card flex flex-col h-full">
            {selectedKbIds.size > 0 ? (
              <>
                {/* 会话信息 */}
                <div className="p-4 border-b border-slate-200 dark:border-slate-600">
                  <h2 className="text-base font-semibold text-slate-800 dark:text-white">
                    {currentSessionTitle || (selectedKbIds.size === 1
                      ? knowledgeBases.find(kb => kb.id === Array.from(selectedKbIds)[0])?.name || '新对话'
                      : `${selectedKbIds.size} 个知识库 - 新对话`)}
                  </h2>
                  <div className="flex flex-wrap gap-1.5 mt-2">
                    {Array.from(selectedKbIds).map(kbId => {
                      const kb = knowledgeBases.find(k => k.id === kbId);
                      return kb ? (
                          <span key={kbId}
                                className="px-2 py-0.5 bg-primary-50 dark:bg-primary-900/30 text-primary-600 dark:text-primary-400 text-xs rounded-full">
                          {kb.name}
                        </span>
                      ) : null;
                    })}
                  </div>
                  {kbNeedsSync && (
                    <div className="mt-3 flex flex-wrap items-center gap-2 rounded-lg bg-amber-50 dark:bg-amber-900/20 px-3 py-2">
                      <p className="text-xs text-amber-800 dark:text-amber-200">本次对话使用的资料已更改</p>
                      <button
                        type="button"
                        disabled={syncingKb}
                        onClick={() => void handleSyncKnowledgeBases()}
                        className="text-xs px-2.5 py-1 rounded-md bg-amber-500 text-white hover:bg-amber-600 disabled:opacity-50"
                      >
                        {syncingKb ? '更新中...' : '更新本次对话'}
                      </button>
                    </div>
                  )}
                </div>

                {/* 消息列表 */}
                <div className="flex-1 min-h-0 relative dark:bg-slate-800">
                  {messages.length === 0 ? (
                      <div
                          className="absolute inset-0 flex flex-col items-center justify-center text-slate-400 dark:text-slate-500">
                      <MessageSquare className="w-12 h-12 mx-auto mb-3 opacity-50" />
                      <p className="text-sm">输入一个问题开始对话</p>
                    </div>
                  ) : (
                    <Virtuoso
                      ref={virtuosoRef}
                      data={messages}
                      initialTopMostItemIndex={messages.length - 1}
                      followOutput="smooth"
                      className="h-full w-full"
                      itemContent={(index, msg) => (
                          <div className="pb-4 px-4 first:pt-4 dark:bg-slate-800">
                          <div
                            className={`flex ${msg.type === 'user' ? 'justify-end' : 'justify-start'}`}
                          >
                            <div
                              className={`max-w-[85%] rounded-lg p-4 ${msg.type === 'user'
                                ? 'bg-primary-600 text-white'
                                  : 'bg-white dark:bg-slate-800 border border-slate-100 dark:border-slate-600 text-slate-800 dark:text-slate-100'
                              }`}
                            >
                              {msg.type === 'user' ? (
                                <p className="whitespace-pre-wrap leading-relaxed text-sm">{msg.content}</p>
                              ) : (
                                  <div className="prose prose-slate dark:prose-invert prose-sm max-w-none">
                                  <ReactMarkdown
                                    remarkPlugins={[remarkGfm]}
                                    components={{
                                      // 自定义代码块渲染
                                      code: ({ className, children }) => {
                                        const match = /language-(\w+)/.exec(className || '');
                                        const isInline = !match;

                                        if (isInline) {
                                          return (
                                              <code
                                                  className="bg-slate-100 dark:bg-slate-600 text-primary-600 dark:text-primary-400 px-1.5 py-0.5 rounded-md text-sm font-normal">
                                              {children}
                                            </code>
                                          );
                                        }

                                        // 代码块使用 CodeBlock 组件
                                        return (
                                          <CodeBlock language={match[1]}>
                                            {String(children).replace(/\n$/, '')}
                                          </CodeBlock>
                                        );
                                      },
                                      // 禁用默认 pre 渲染，由 CodeBlock 处理
                                      pre: ({ children }) => <>{children}</>,
                                    }}
                                  >
                                    {formatMarkdown(msg.content)}
                                  </ReactMarkdown>
                                  {loading && index === messages.length - 1 && (
                                    <span className="inline-block w-0.5 h-5 bg-primary-500 ml-1 animate-pulse" />
                                  )}
                                  {/* 流式阶段进度气泡（来自 progress: 前缀事件） */}
                                  {loading && index === messages.length - 1 && cardMessage && (
                                    <div className="mt-2 text-sm text-amber-700 dark:text-amber-300 bg-amber-50 dark:bg-amber-900/20 rounded-lg px-3 py-2">
                                      {cardMessage}
                                    </div>
                                  )}
                                  {index === messages.length - 1 && cardChoices.length > 0 && (
                                    <div className="mt-2 flex flex-wrap gap-2">
                                      {cardChoices.map(choice => (
                                        <button
                                          key={choice.id}
                                          type="button"
                                          className="text-xs px-3 py-1.5 rounded-full border border-primary-200 text-primary-700 hover:bg-primary-50 dark:border-primary-700 dark:text-primary-300"
                                          onClick={() => {
                                            setQuestion(buildRagCardFollowUp(choice));
                                          }}
                                        >
                                          {choice.label}
                                        </button>
                                      ))}
                                    </div>
                                  )}
                                  {loading && index === messages.length - 1 && progressText && (
                                    <div className="mt-2 inline-flex items-center gap-1.5 text-xs text-primary-600 dark:text-primary-400 bg-primary-50 dark:bg-primary-900/20 rounded-full px-2.5 py-1">
                                      <span className="inline-block w-1.5 h-1.5 rounded-full bg-primary-500 animate-pulse" />
                                      {progressText}
                                    </div>
                                  )}
                                  {loading && index === messages.length - 1 && rewrittenQuestion && (
                                    <div className="mt-2 text-xs text-slate-500 dark:text-slate-400 bg-slate-50 dark:bg-slate-800/60 rounded-lg px-3 py-2">
                                      检索优化：{rewrittenQuestion}
                                    </div>
                                  )}
                                  {/* 当前回答的引用来源；citation 终态到达后显示实际引用状态。 */}
                                  {index === messages.length - 1 && activeSources && activeSources.length > 0 && (
                                    <div className="mt-2 border-t border-slate-100 dark:border-slate-700 pt-2 space-y-1">
                                      <p className="text-xs text-slate-400 dark:text-slate-500">参考来源</p>
                                      {activeSources.slice(0, 3).map((s, i) => (
                                        <div key={i} className="text-xs text-slate-500 dark:text-slate-400 truncate">
                                          {i + 1}. {s.documentTitle}
                                          {s.similarity != null && (
                                            <span className="ml-1 text-slate-400">（相关度 {s.similarity.toFixed(2)}）</span>
                                          )}
                                          {citationStatusLabel(s, citationFinalized) && (
                                            <span className={`ml-1 ${s.cited ? 'text-emerald-600 dark:text-emerald-400' : 'text-amber-600 dark:text-amber-400'}`}>
                                              {citationStatusLabel(s, citationFinalized)}
                                            </span>
                                          )}
                                        </div>
                                      ))}
                                      {activeSources.length > 3 && (
                                        <p className="text-xs text-slate-400">等 {activeSources.length} 个来源</p>
                                      )}
                                    </div>
                                  )}
                                </div>
                              )}
                            </div>
                          </div>
                        </div>
                      )}
                    />
                  )}
                </div>

                {/* 输入区域 */}
                <div className="p-4 border-t border-slate-200 dark:border-slate-600">
                  <div className="flex gap-3">
                    <input
                      type="text"
                      value={question}
                      onChange={(e) => setQuestion(e.target.value)}
                      onKeyPress={(e) => e.key === 'Enter' && !e.shiftKey && handleSubmitQuestion()}
                      placeholder="输入问题，按 Enter 发送"
                      className="dark-input flex-1 px-3 py-2.5 text-sm"
                      disabled={loading}
                    />
                    <button
                      onClick={handleSubmitQuestion}
                      disabled={!question.trim() || selectedKbIds.size === 0 || loading}
                      className="btn-primary px-4 py-2.5 disabled:opacity-50 disabled:cursor-not-allowed text-sm"
                    >
                      发送
                    </button>
                  </div>
                </div>
              </>
            ) : (
                <div className="flex-1 flex items-center justify-center text-slate-400 dark:text-slate-500">
                <div className="text-center">
                  <MessageSquare className="w-12 h-12 mx-auto mb-3 opacity-50" />
                  <p className="text-sm">请先在右侧选择资料</p>
                </div>
              </div>
            )}
          </div>
        </div>

        {/* 右侧：知识库选择（简化版） */}
        {rightPanelOpen && (
            <div className="min-w-0">
              <div
                  className="surface-card flex max-h-[32rem] w-full flex-col p-3 lg:h-full lg:max-h-none">
                <div className="flex items-center justify-between mb-4">
                  <h2 className="text-base font-semibold text-slate-800 dark:text-white">选择资料</h2>
                  <button
                    onClick={() => setRightPanelOpen(false)}
                    className="p-1 text-slate-400 hover:text-slate-600 dark:hover:text-slate-300 rounded"
                    aria-label="收起资料面板"
                  >
                    <ChevronLeft className="w-5 h-5" />
                  </button>
                </div>

                {/* 搜索框 */}
                <div className="flex gap-2 mb-3">
                  <input
                    type="text"
                    value={searchKeyword}
                    onChange={(e) => setSearchKeyword(e.target.value)}
                    onKeyPress={(e) => e.key === 'Enter' && handleSearch()}
                    placeholder="搜索..."
                    className="flex-1 px-3 py-1.5 text-sm border border-slate-200 dark:border-slate-600 rounded-lg focus:outline-none focus:ring-1 focus:ring-primary-500 bg-white dark:bg-slate-700 text-slate-900 dark:text-white placeholder-slate-400"
                  />
                  <button
                    onClick={handleSearch}
                    className="px-3 py-1.5 text-sm bg-primary-500 text-white rounded-lg hover:bg-primary-600"
                  >
                    搜索
                  </button>
                </div>

                {/* 排序 */}
                <div className="mb-3">
                  <KnowledgeBaseSortSelect
                    value={sortBy}
                    onChange={(value) => {
                      setSortBy(value);
                      setSearchKeyword('');
                    }}
                    compact
                  />
                </div>

                {/* 知识库列表 */}
                <div className="flex-1 overflow-y-auto">
                  {loadingList ? (
                    <LoadingState compact />
                  ) : knowledgeBases.length === 0 ? (
                    <EmptyState
                      className="text-center py-6 text-slate-500 dark:text-slate-400"
                      title={searchKeyword ? '未找到' : '暂无知识库'}
                      titleClassName="mb-2 text-sm"
                      action={!searchKeyword && (
                        <button
                          onClick={onUpload}
                          className="text-primary-500 hover:text-primary-600 font-medium text-sm"
                        >
                          立即上传
                        </button>
                      )}
                    />
                  ) : (
                    <div className="space-y-2">
                      {groupedKnowledgeBases.map((group) => (
                          <div key={group.name}
                               className="border border-slate-100 dark:border-slate-700 rounded-lg overflow-hidden">
                          <button
                            onClick={() => toggleCategory(group.name)}
                            aria-expanded={group.isExpanded}
                            className="w-full flex items-center justify-between px-3 py-2 bg-slate-50 dark:bg-slate-700/50 hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors"
                          >
                            <div className="flex items-center gap-2">
                              <ChevronRight
                                className={`w-3.5 h-3.5 text-slate-400 transition-transform ${group.isExpanded ? 'rotate-90' : ''}`}
                              />
                              <span
                                  className="font-medium text-slate-700 dark:text-slate-300 text-sm">{group.name}</span>
                            </div>
                            <span className="text-xs text-slate-400">{group.items.length}</span>
                          </button>

                          {group.isExpanded && (
                              <div className="overflow-hidden">
                                <div className="p-2 space-y-1">
                                  {group.items.map((kb) => (
                                    <label
                                      key={kb.id}
                                      className={`block cursor-pointer rounded-lg p-2 transition-colors focus-within:ring-2 focus-within:ring-primary-500 ${selectedKbIds.has(kb.id)
                                          ? 'bg-primary-50 dark:bg-primary-900/30 border border-primary-500'
                                          : 'bg-white dark:bg-slate-700/50 hover:bg-slate-50 dark:hover:bg-slate-700 border border-transparent'
                                        }`}
                                    >
                                      <div className="flex items-center gap-2">
                                        <input
                                          type="checkbox"
                                          checked={selectedKbIds.has(kb.id)}
                                          onChange={() => handleToggleKb(kb.id)}
                                          className="w-3.5 h-3.5 text-primary-500 rounded focus:ring-primary-500"
                                        />
                                        <span
                                            className="font-medium text-slate-800 dark:text-white text-xs truncate flex-1">{kb.name}</span>
                                      </div>
                                      <p className="text-xs text-slate-400 dark:text-slate-500 mt-0.5 ml-5">{formatFileSize(kb.fileSize)}</p>
                                    </label>
                                  ))}
                                </div>
                              </div>
                          )}
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              </div>
            </div>
        )}

        {/* 收起状态下的展开按钮 */}
        {!rightPanelOpen && (
          <button
            onClick={() => setRightPanelOpen(true)}
            className="surface-card flex-shrink-0 w-10 flex items-center justify-center hover:bg-slate-50 dark:hover:bg-slate-700 transition-colors"
            title="展开资料面板"
          >
            <ChevronRight className="w-5 h-5 text-slate-400" />
          </button>
        )}
      </div>

      {/* 删除会话确认弹窗 */}
      <DeleteConfirmDialog
        open={!!sessionDeleteConfirm}
        item={sessionDeleteConfirm ? { title: sessionDeleteConfirm.title } : null}
        itemType="对话"
        onConfirm={handleDeleteSession}
        onCancel={() => setSessionDeleteConfirm(null)}
      />

      {/* 编辑会话标题弹窗 */}
      {editingSessionTitle && (
          <>
            <div
              onClick={() => {
                setEditingSessionTitle(null);
                setNewSessionTitle('');
              }}
              className="fixed inset-0 bg-black/50 z-50"
            />
            <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
              <div
                onClick={(e) => e.stopPropagation()}
                className="surface-card max-w-md w-full p-5"
              >
                <h3 className="text-xl font-bold text-slate-900 dark:text-white mb-4">编辑标题</h3>
                <input
                  type="text"
                  value={newSessionTitle}
                  onChange={(e) => setNewSessionTitle(e.target.value)}
                  onKeyPress={(e) => e.key === 'Enter' && handleSaveSessionTitle()}
                  placeholder="请输入新标题"
                  className="dark-input w-full px-3 py-2.5 text-sm mb-4"
                  autoFocus
                />
                <div className="flex justify-end gap-3">
                  <button
                    onClick={() => {
                      setEditingSessionTitle(null);
                      setNewSessionTitle('');
                    }}
                    className="px-4 py-2 text-sm text-slate-600 dark:text-slate-400 hover:text-slate-800 dark:hover:text-white"
                  >
                    取消
                  </button>
                  <button
                    onClick={handleSaveSessionTitle}
                    disabled={!newSessionTitle.trim()}
                    className="px-4 py-2 text-sm bg-primary-500 text-white rounded-lg hover:bg-primary-600 disabled:opacity-50"
                  >
                    保存
                  </button>
                </div>
              </div>
            </div>
          </>
      )}

      {evalOpen && (
          <div
            className="fixed inset-0 bg-black/50 z-50 flex items-center justify-center p-4"
            onClick={() => setEvalOpen(false)}
          >
            <div
              onClick={(e) => e.stopPropagation()}
              className="surface-card max-w-2xl w-full p-5"
            >
              <div className="flex items-center justify-between mb-1">
                <h3 className="text-xl font-bold text-slate-900 dark:text-white flex items-center gap-2">
                  <BarChart3 className="w-5 h-5 text-primary-500" />
                  检索评测
                </h3>
                <button
                  onClick={() => setEvalOpen(false)}
                  className="p-1.5 text-slate-400 hover:text-slate-600 dark:hover:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-700 rounded-lg"
                  aria-label="关闭检索评测"
                >
                  <X className="w-5 h-5" />
                </button>
              </div>
              <p className="text-xs text-slate-400 dark:text-slate-500 mb-4 leading-relaxed">
                 用 JSON 用例检查所选资料的检索质量，并对比 Hit@K、MRR 和 NDCG 等指标。
              </p>
              <textarea
                value={evalInput}
                onChange={(e) => setEvalInput(e.target.value)}
                placeholder='[{"question":"JVM GC 是什么？","expectedKeywords":["垃圾回收"],"expectedChunkIds":[]}]'
                className="dark-input w-full h-44 px-3 py-2.5 text-sm font-mono"
              />
              {evalError && (
                <p className="mt-2 text-sm text-red-500">{evalError}</p>
              )}
              {evalResult && (
                <>
                  <div className="grid grid-cols-5 gap-2 my-4 text-center">
                    {[
                      ['Hit@K', evalResult.hitRate],
                      ['MRR', evalResult.mrr],
                      ['NDCG', evalResult.ndcg],
                      ['检索召回', evalResult.retrievalRecall],
                      ['检索精确', evalResult.retrievalPrecision],
                    ].map(([label, value]) => (
                      <div key={label} className="rounded-lg bg-slate-50 dark:bg-slate-700 p-3">
                        <p className="text-xs text-slate-500 dark:text-slate-400">{label}</p>
                        <p className="text-lg font-semibold text-slate-900 dark:text-white">
                          {Number(value).toFixed(2)}
                        </p>
                      </div>
                    ))}
                  </div>
                  <div className="max-h-72 overflow-y-auto space-y-3 pr-1">
                    {evalResult.items.map((item, index) => (
                      <div key={`${item.question}-${index}`} className="rounded-lg border border-slate-100 dark:border-slate-700 p-3">
                        <div className="flex items-center justify-between gap-3">
                          <p className="text-sm font-medium text-slate-800 dark:text-white truncate">{item.question}</p>
                          <span className={`text-xs font-medium ${item.hit ? 'text-green-600' : 'text-red-500'}`}>
                            {item.hit ? `命中 #${item.firstHitRank}` : '未命中'}
                          </span>
                        </div>
                        <div className="mt-2 space-y-2">
                          {item.retrievedSegments.slice(0, evalResult.k).map(segment => (
                            <div key={`${segment.rank}-${segment.chunkId}`} className="rounded bg-slate-50 dark:bg-slate-700/60 p-2">
                              <p className="text-xs text-slate-500 dark:text-slate-400">
                                #{segment.rank} chunk={segment.chunkId || '-'} doc={segment.docId ?? '-'} score={segment.score ?? '-'}
                              </p>
                              <p className="text-xs text-slate-700 dark:text-slate-200 line-clamp-2">{segment.snippet}</p>
                            </div>
                          ))}
                        </div>
                      </div>
                    ))}
                  </div>
                </>
              )}
              <div className="flex justify-end gap-3 mt-4">
                <button
                  onClick={() => setEvalOpen(false)}
                  className="px-4 py-2 text-sm text-slate-600 dark:text-slate-400 hover:text-slate-800 dark:hover:text-white"
                >
                  关闭
                </button>
                <button
                  onClick={handleExportQa}
                  disabled={exportLoading || !evalInput.trim()}
                  className="px-4 py-2 text-sm border border-slate-200 dark:border-slate-600 rounded-lg hover:bg-slate-50 dark:hover:bg-slate-700 disabled:opacity-50"
                >
                  {exportLoading ? '导出中...' : 'RAGAS 导出'}
                </button>
                <button
                  onClick={handleRunEval}
                  disabled={evalLoading || !evalInput.trim()}
                  className="px-4 py-2 text-sm bg-primary-500 text-white rounded-lg hover:bg-primary-600 disabled:opacity-50"
                >
                  {evalLoading ? '评测中...' : '开始评测'}
                </button>
              </div>
              {exportResult && (
                <p className="mt-3 text-xs text-emerald-600 dark:text-emerald-400">
                  已导出 {exportResult.total} 条 QA 样本，可用于 eval/ragas。
                </p>
              )}
              <div className="mt-4 pt-4 border-t border-slate-100 dark:border-slate-700">
                <p className="text-sm font-medium text-slate-700 dark:text-slate-200 mb-2">单题样本生成</p>
                <div className="flex gap-2">
                  <input
                    type="text"
                    value={datasetQuestion}
                    onChange={(e) => setDatasetQuestion(e.target.value)}
                    placeholder="输入一个问题生成评测样本"
                    className="flex-1 px-3 py-2 text-sm border border-slate-200 dark:border-slate-600 rounded-lg bg-white dark:bg-slate-700"
                  />
                  <button
                    type="button"
                    disabled={datasetLoading || !datasetQuestion.trim()}
                    onClick={() => void handleGenerateDataset()}
                    className="px-3 py-2 text-sm border border-slate-200 dark:border-slate-600 rounded-lg disabled:opacity-50"
                  >
                    {datasetLoading ? '生成中...' : '生成'}
                  </button>
                </div>
                {datasetResult && (
                  <div className="mt-2 rounded-lg bg-slate-50 dark:bg-slate-700/60 p-3 text-xs text-slate-700 dark:text-slate-200 whitespace-pre-wrap max-h-32 overflow-y-auto">
                    {datasetResult.answer}
                  </div>
                )}
              </div>
            </div>
          </div>
      )}

      {debugOpen && (
          <div
            className="fixed inset-0 bg-black/50 z-50 flex items-center justify-center p-4"
            onClick={() => setDebugOpen(false)}
          >
            <div
              onClick={(e) => e.stopPropagation()}
              className="surface-card max-w-2xl w-full p-5"
            >
              <div className="flex items-center justify-between mb-1">
                <h3 className="text-xl font-bold text-slate-900 dark:text-white flex items-center gap-2">
                  <Bug className="w-5 h-5 text-primary-500" />
                  检索链路调试
                </h3>
                <button
                  onClick={() => setDebugOpen(false)}
                  className="p-1.5 text-slate-400 hover:text-slate-600 dark:hover:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-700 rounded-lg"
                  aria-label="关闭链路调试"
                >
                  <X className="w-5 h-5" />
                </button>
              </div>
              <p className="text-xs text-slate-400 dark:text-slate-500 mb-4 leading-relaxed">
                 输入一个问题，分别查看意图识别、提示词组装、问题改写和重排结果。
              </p>
              <input
                type="text"
                value={debugQuestion}
                onChange={(e) => setDebugQuestion(e.target.value)}
                placeholder="输入测试问题"
                className="dark-input w-full px-3 py-2.5 text-sm mb-3"
              />
              <div className="flex flex-wrap gap-2 mb-4">
                {([
                  ['intent', '意图识别'],
                  ['prompt', 'Prompt'],
                  ['rewrite', 'Query 改写'],
                  ['rerank', 'Rerank'],
                ] as const).map(([mode, label]) => (
                  <button
                    key={mode}
                    type="button"
                    disabled={debugLoading || !debugQuestion.trim()}
                    onClick={() => void handleRunDebug(mode)}
                    className="px-3 py-1.5 text-sm rounded-lg border border-slate-200 dark:border-slate-600 hover:bg-slate-50 dark:hover:bg-slate-700 disabled:opacity-50"
                  >
                    {label}
                  </button>
                ))}
              </div>
              <pre className="max-h-64 overflow-y-auto rounded-lg bg-slate-50 dark:bg-slate-900/40 p-3 text-xs text-slate-700 dark:text-slate-200 whitespace-pre-wrap">
                {debugLoading ? '请求中...' : (debugOutput || '选择上方模块运行调试')}
              </pre>
            </div>
          </div>
      )}
    </div>
  );
}
