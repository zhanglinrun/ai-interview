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
import {ragChatApi, type IntentStreamResult, type RagCardChoice, type RagChatSessionListItem, type RagRouteResult, type RagSourceDTO} from '../api/ragChat';
import {ragModuleApi} from '../api/ragModule';
import {getErrorMessage} from '../api/request';
import {formatTimeAgo} from '../utils/date';
import {formatFileSize} from '../utils/format';
import DeleteConfirmDialog from '../components/DeleteConfirmDialog';
import KnowledgeBaseSortSelect from '../components/KnowledgeBaseSortSelect';
import CodeBlock from '../components/CodeBlock';
import {EmptyState, LoadingState} from '../components/PageState';
import SegmentedControl from '../components/ui/SegmentedControl';
import {intentLabel} from './evalRunDisplay';
import {
  BarChart3,
  Bug,
  ChevronLeft,
  ChevronRight,
  Edit,
  FileText,
  MessageSquare,
  Pin,
  Plus,
  Search,
  Send,
  Sparkles,
  Trash2,
  Upload,
  X,
} from 'lucide-react';
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

/**
 * 卡片点选后的追问文案：带上稳定 ID，便于下一轮意图门规则/LLM 抽出实体后跳过澄清卡。
 * 句式对齐后端 FusionIntentRecognitionService 的简历/会话 ID 正则。
 */
export function buildRagCardFollowUp(choice: RagCardChoice): string {
  switch (choice.type) {
    case 'schedule':
      return `请查询面试安排 ID=${choice.id}（${choice.label}）`;
    case 'session':
      return `请总结这场面试，会话 ID=${choice.id}（${choice.label}）`;
    case 'jobTrack':
      return `请针对「${choice.label}」方向（jobTrack=${choice.id}）给出面试准备建议`;
    case 'resume':
    default:
      return `请分析简历 ID=${choice.id}（${choice.label}）`;
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

type GroundedStatus = 'pass' | 'grounded' | 'need_escalate';

function isGroundedStatus(status: string): status is GroundedStatus {
  return status === 'pass' || status === 'grounded' || status === 'need_escalate';
}

export function groundedStatusLabel(status: string | null | undefined): string | null {
  if (!status) return null;
  if (!isGroundedStatus(status)) {
    return `grounded: ${status}`;
  }
  switch (status) {
    case 'pass':
      return 'grounded: pass';
    case 'grounded':
      return 'grounded: grounded';
    case 'need_escalate':
      return 'grounded: need_escalate';
    default: {
      const _exhaustive: never = status;
      return `grounded: ${_exhaustive}`;
    }
  }
}

function strategyScore(intent: IntentStreamResult, name: string): number | null {
  const hit = (intent.strategies ?? []).find(s => s.strategy === name);
  return hit ? hit.confidence : null;
}

const SUGGESTED_PROMPTS = [
  '这份资料最核心的三个概念是什么？',
  '用一段话解释关键机制，并标出依据',
  '哪些点最容易在面试里被追问？',
] as const;

type MobilePane = 'history' | 'chat' | 'sources';

function formatMarkdown(text: string): string {
  if (!text) return '';
  return text
    .replace(/\\n/g, '\n')
    .replace(/^(#{1,6})([^\s#\n])/gm, '$1 $2')
    .replace(/^(\s*)(\d+)\.([^\s\n])/gm, '$1$2. $3')
    .replace(/^(\s*[-*])([^\s\n-])/gm, '$1 $2')
    .replace(/\n{3,}/g, '\n\n');
}

export function groundedStatusDisplay(status: string | null | undefined): { label: string; warn: boolean } | null {
  if (!status) return null;
  if (status === 'need_escalate') return { label: '依据不足', warn: true };
  if (status === 'pass' || status === 'grounded') return { label: '依据充分', warn: false };
  return { label: groundedStatusLabel(status) ?? status, warn: true };
}

export function isQueryableKnowledgeBase(kb: { docStatus?: string | null }): boolean {
  return kb.docStatus === 'VECTOR_STORED';
}

export function filterQueryableKnowledgeBases<T extends { docStatus?: string | null }>(items: T[]): T[] {
  return items.filter(isQueryableKnowledgeBase);
}

export function knowledgeBaseCategoryName(category?: string | null): string {
  return category?.trim() ? category : '未分类';
}

export function collectKnowledgeBaseCategories(
  items: Array<{ category?: string | null }>,
): string[] {
  return Array.from(new Set(items.map((item) => knowledgeBaseCategoryName(item.category))));
}

export function categoriesForSelectedKnowledgeBases(
  items: Array<{ id: number; category?: string | null }>,
  selectedIds: Iterable<number>,
): string[] {
  const selected = new Set(selectedIds);
  return collectKnowledgeBaseCategories(items.filter((item) => selected.has(item.id)));
}

export function mergeCategoryNames(current: Iterable<string>, extra: Iterable<string>): Set<string> {
  return new Set([...current, ...extra]);
}

/** 已勾选资料，或已经打开一条历史对话时，显示问答区而不是「先选资料」空态。 */
export function shouldShowQueryChatPane(
  selectedKbCount: number,
  currentSessionId: number | null,
): boolean {
  return selectedKbCount > 0 || currentSessionId != null;
}

/** 历史消息里曾把「参考来源」Markdown 附录拼进正文，展示时剥掉，避免和卡片重复。 */
export function stripPersistedSourceAppendix(content: string): string {
  if (!content) return '';
  return content
    .replace(/\n---\n+\*\*参考来源：\*\*[\s\S]*$/u, '')
    .replace(/\n---\n+## 参考来源[\s\S]*$/u, '')
    .replace(/\n## 参考来源[\s\S]*$/u, '')
    .trimEnd();
}

export function cleanSourceSnippet(snippet?: string | null): string {
  if (!snippet) return '';
  return snippet
    .replace(/!\[[^\]]*]\([^)]*\)/g, ' ')
    .replace(/\[[^\]]*]\([^)]*\)/g, ' ')
    .replace(/https?:\/\/\S+/g, ' ')
    .replace(/[#*_`>]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

export interface RagSourceGroup {
  title: string;
  items: Array<{ source: RagSourceDTO; index: number }>;
}

export function groupRagSourcesByDocument(sources: RagSourceDTO[]): RagSourceGroup[] {
  const groups: RagSourceGroup[] = [];
  const indexByTitle = new Map<string, number>();
  sources.forEach((source, index) => {
    const title = source.documentTitle || source.sourceName || '未知资料';
    const existing = indexByTitle.get(title);
    if (existing == null) {
      indexByTitle.set(title, groups.length);
      groups.push({ title, items: [{ source, index }] });
      return;
    }
    groups[existing].items.push({ source, index });
  });
  return groups;
}

function formatMessageTime(timestamp: Date): string {
  return timestamp.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
}

function ChatMarkdown({ content }: { content: string }) {
  return (
    <div className="prose prose-stone dark:prose-invert prose-sm max-w-none prose-p:leading-relaxed prose-pre:my-3 prose-pre:bg-transparent prose-pre:p-0">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          img: () => (
            <span className="my-1 inline-flex items-center rounded-md bg-stone-100 px-1.5 py-0.5 text-[11px] text-stone-400 dark:bg-stone-800">
              图片
            </span>
          ),
          code: ({ className, children }) => {
            const match = /language-(\w+)/.exec(className || '');
            const isInline = !match;
            if (isInline) {
              return (
                <code className="rounded-md bg-stone-100 px-1.5 py-0.5 text-sm font-normal text-primary-700 dark:bg-stone-800 dark:text-primary-300">
                  {children}
                </code>
              );
            }
            return (
              <CodeBlock language={match[1]}>
                {String(children).replace(/\n$/, '')}
              </CodeBlock>
            );
          },
          pre: ({ children }) => <>{children}</>,
        }}
      >
        {formatMarkdown(stripPersistedSourceAppendix(content))}
      </ReactMarkdown>
    </div>
  );
}

function AssistantTrace({
  intentResult,
  routeResult,
  rewrittenQuestion,
  progressText,
  loading,
  activeSources,
  citationFinalized,
  citationConfidence,
  invalidCitations,
  groundedStatus,
}: {
  intentResult: IntentStreamResult | null;
  routeResult: RagRouteResult | null;
  rewrittenQuestion: string;
  progressText: string;
  loading: boolean;
  activeSources: RagSourceDTO[] | null;
  citationFinalized: boolean;
  citationConfidence: number | null;
  invalidCitations: number[];
  groundedStatus: string | null;
}) {
  const [open, setOpen] = useState(false);
  const grounded = groundedStatusDisplay(groundedStatus);
  const sources = activeSources ?? [];
  const sourceGroups = groupRagSourcesByDocument(sources);
  const hasTrace = Boolean(intentResult || routeResult || rewrittenQuestion || sources.length > 0 || progressText);

  if (!hasTrace) return null;

  return (
    <div className="mt-3 border-t border-stone-200/80 pt-3 dark:border-stone-700">
      {loading && progressText && (
        <div className="mb-2 inline-flex items-center gap-1.5 rounded-full bg-primary-50 px-2.5 py-1 text-xs text-primary-700 dark:bg-primary-950/40 dark:text-primary-300">
          <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-primary-500" />
          {progressText}
        </div>
      )}

      {sources.length > 0 && (
        <div className="space-y-2">
          <div className="flex flex-wrap items-center gap-2">
            <p className="text-[11px] font-medium tracking-wide text-stone-400">参考来源</p>
            <span className="text-[11px] text-stone-400">
              {sources.length} 个片段
              {sourceGroups.length > 1 ? ` · ${sourceGroups.length} 份资料` : ''}
            </span>
            {citationFinalized && citationConfidence != null && (
              <span className="rounded-md bg-emerald-50 px-1.5 py-0.5 text-[11px] text-emerald-700 dark:bg-emerald-950/40 dark:text-emerald-300">
                引用 {(citationConfidence * 100).toFixed(0)}%
              </span>
            )}
            {grounded && (
              <span className={`rounded-md px-1.5 py-0.5 text-[11px] ${
                grounded.warn
                  ? 'bg-amber-50 text-amber-700 dark:bg-amber-950/40 dark:text-amber-300'
                  : 'bg-emerald-50 text-emerald-700 dark:bg-emerald-950/40 dark:text-emerald-300'
              }`}>
                {grounded.label}
              </span>
            )}
          </div>
          {sourceGroups.map((group) => (
            <div
              key={group.title}
              className="overflow-hidden rounded-xl border border-stone-200/80 bg-stone-50/90 dark:border-stone-700 dark:bg-stone-900/50"
            >
              <div className="flex items-center gap-2 border-b border-stone-200/70 px-3 py-2 dark:border-stone-800">
                <FileText className="h-3.5 w-3.5 shrink-0 text-stone-400" />
                <p className="min-w-0 flex-1 truncate text-xs font-medium text-stone-700 dark:text-stone-200">
                  {group.title}
                </p>
                {group.items.length > 1 && (
                  <span className="shrink-0 text-[11px] text-stone-400">{group.items.length} 处</span>
                )}
              </div>
              <ol className="divide-y divide-stone-200/70 dark:divide-stone-800">
                {group.items.map(({ source, index }) => {
                  const snippet = cleanSourceSnippet(source.snippet);
                  const status = citationStatusLabel(source, citationFinalized);
                  return (
                    <li key={`${group.title}-${index}`} className="px-3 py-2">
                      <div className="flex flex-wrap items-center gap-1.5">
                        <span className="inline-flex h-5 min-w-5 items-center justify-center rounded-md bg-white px-1 text-[11px] font-medium text-stone-600 ring-1 ring-stone-200/80 dark:bg-stone-950 dark:text-stone-300 dark:ring-stone-700">
                          {index + 1}
                        </span>
                        {source.sectionTitle && (
                          <span className="max-w-[12rem] truncate text-[11px] text-stone-500 dark:text-stone-400">
                            {source.sectionTitle}
                          </span>
                        )}
                        {status && (
                          <span className={`text-[11px] ${source.cited
                            ? 'text-emerald-600 dark:text-emerald-400'
                            : 'text-amber-600 dark:text-amber-400'}`}
                          >
                            {status}
                          </span>
                        )}
                      </div>
                      {snippet && (
                        <p className="mt-1 line-clamp-2 text-[11px] leading-5 text-stone-500 dark:text-stone-400">
                          {snippet}
                        </p>
                      )}
                    </li>
                  );
                })}
              </ol>
            </div>
          ))}
        </div>
      )}

      {(intentResult || routeResult || rewrittenQuestion || invalidCitations.length > 0) && (
        <div className="mt-2">
          <button
            type="button"
            onClick={() => setOpen((value) => !value)}
            className="text-[11px] text-stone-400 transition-colors hover:text-stone-700 dark:hover:text-stone-200"
          >
            {open ? '收起检索过程' : '查看检索过程'}
          </button>
          {open && (
            <div className="mt-2 space-y-1.5 rounded-lg bg-stone-100/80 px-3 py-2 text-xs leading-5 text-stone-500 dark:bg-stone-800/70 dark:text-stone-400">
              {intentResult && (
                <p>
                  意图 {intentLabel(intentResult.intent)}
                  {intentResult.confidence != null && ` · ${(intentResult.confidence * 100).toFixed(0)}%`}
                  {` · ${intentResult.related ? '相关' : '不相关'}`}
                  <span className="ml-1 text-stone-400">
                    llm={strategyScore(intentResult, 'llm')?.toFixed(2) ?? '-'}
                    {' / '}vector={strategyScore(intentResult, 'vector')?.toFixed(2) ?? '-'}
                    {' / '}rule={strategyScore(intentResult, 'rule')?.toFixed(2) ?? '-'}
                  </span>
                </p>
              )}
              {routeResult && (
                <p>
                  路由 {routeResult.source}
                  {routeResult.confidence != null && ` · ${(routeResult.confidence * 100).toFixed(0)}%`}
                  {routeResult.reasoning && ` · ${routeResult.reasoning}`}
                </p>
              )}
              {rewrittenQuestion && <p>检索问法：{rewrittenQuestion}</p>}
              {citationFinalized && invalidCitations.length > 0 && (
                <p className="text-amber-600 dark:text-amber-400">无效编号 [{invalidCitations.join(', ')}]</p>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  );
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
  const [mobilePane, setMobilePane] = useState<MobilePane>('chat');

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
  const [citationConfidence, setCitationConfidence] = useState<number | null>(null);
  const [invalidCitations, setInvalidCitations] = useState<number[]>([]);
  const [groundedStatus, setGroundedStatus] = useState<string | null>(null);
  const [intentResult, setIntentResult] = useState<IntentStreamResult | null>(null);
  const [routeResult, setRouteResult] = useState<RagRouteResult | null>(null);
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
  const [sessionError, setSessionError] = useState('');
  const [syncingKb, setSyncingKb] = useState(false);
  const [debugOpen, setDebugOpen] = useState(false);
  const [debugQuestion, setDebugQuestion] = useState('');
  const [debugOutput, setDebugOutput] = useState('');
  const [debugLoading, setDebugLoading] = useState(false);

  // refs
  const virtuosoRef = useRef<VirtuosoHandle>(null);
  const rafRef = useRef<number>();
  const composerRef = useRef<HTMLTextAreaElement>(null);

  const [, startTransition] = useTransition();

  const applyQueryableKnowledgeBases = useCallback((list: KnowledgeBaseItem[]) => {
    const queryable = filterQueryableKnowledgeBases(list);
    setKnowledgeBases(queryable);
    setExpandedCategories((prev) => mergeCategoryNames(prev, collectKnowledgeBaseCategories(queryable)));
  }, []);

  const resetAssistantDecorations = useCallback(() => {
    setActiveSources(null);
    setCitationFinalized(false);
    setCitationConfidence(null);
    setInvalidCitations([]);
    setGroundedStatus(null);
    setIntentResult(null);
    setRouteResult(null);
    setRewrittenQuestion('');
    setProgressText('');
    setCardMessage('');
    setCardChoices([]);
  }, []);

  const loadKnowledgeBases = useCallback(async () => {
    setLoadingList(true);
    try {
      // 问答助手只显示向量化完成的知识库
      const list = await knowledgeBaseApi.getAllKnowledgeBases(sortBy, 'VECTOR_STORED');
      applyQueryableKnowledgeBases(list);
    } catch (err) {
      console.error('加载知识库列表失败', err);
    } finally {
      setLoadingList(false);
    }
  }, [applyQueryableKnowledgeBases, sortBy]);

  const handleSearch = async () => {
    if (!searchKeyword.trim()) {
      loadKnowledgeBases();
      return;
    }
    setLoadingList(true);
    try {
      const list = await knowledgeBaseApi.search(searchKeyword.trim());
      applyQueryableKnowledgeBases(list);
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
    setSessionError('');
    resetAssistantDecorations();
  };

  const handleLoadSession = async (sessionId: number) => {
    if (loading) return;
    setMobilePane('chat');
    setSessionError('');
    try {
      const detail = await ragChatApi.getSessionDetail(sessionId);
      setCurrentSessionId(detail.id);
      setCurrentSessionTitle(detail.title);
      const kbIds = new Set(detail.knowledgeBases.map(kb => kb.id));
      setSelectedKbIds(kbIds);
      setSessionBoundKbIds(kbIds);
      setExpandedCategories((prev) => mergeCategoryNames(
        prev,
        categoriesForSelectedKnowledgeBases(knowledgeBases, kbIds),
      ));
      resetAssistantDecorations();
      setMessages(detail.messages.map(m => ({
        id: m.id,
        type: m.type,
        content: m.content,
        timestamp: new Date(m.createdAt),
      })));
    } catch (err) {
      console.error('加载会话失败', err);
      setSessionError(getErrorMessage(err, '加载对话失败，请重试'));
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

  const selectedKnowledgeBases = useMemo(
    () => knowledgeBases.filter((kb) => selectedKbIds.has(kb.id)),
    [knowledgeBases, selectedKbIds],
  );

  const sessionHeading = currentSessionTitle || (selectedKnowledgeBases.length === 1
    ? selectedKnowledgeBases[0].name
    : selectedKnowledgeBases.length > 1
      ? `${selectedKnowledgeBases.length} 份资料`
      : '新对话');
  const showChatPane = shouldShowQueryChatPane(selectedKbIds.size, currentSessionId);
  const missingQueryableMaterials = showChatPane && selectedKbIds.size === 0;

  const resizeComposer = (value: string) => {
    const el = composerRef.current;
    if (!el) return;
    el.style.height = 'auto';
    el.style.height = `${Math.min(Math.max(el.scrollHeight, 44), 160)}px`;
    if (!value) {
      el.style.height = '44px';
    }
  };

  /** 提交提问；卡片点选可传入 followUp，避免等 setState 后再读 question。 */
  const submitQuestion = async (rawQuestion?: string) => {
    const userQuestion = (rawQuestion ?? question).trim();
    if (!userQuestion || selectedKbIds.size === 0 || loading) return;

    setQuestion('');
    resizeComposer('');
    setLoading(true);
    resetAssistantDecorations();

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
          // 改写问法与引用来源在回答结束后仍保留，便于答辩时对照 Trace。
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
          setCitationConfidence(null);
          setInvalidCitations([]);
          setGroundedStatus(null);
          setIntentResult(null);
          setRouteResult(null);
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
          setCitationConfidence(metadata.confidence);
          setInvalidCitations(metadata.invalidCitations ?? []);
          setGroundedStatus(metadata.groundedStatus ?? null);
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
        },
        (intent) => {
          setIntentResult(intent);
        },
        (route) => {
          setRouteResult(route);
        }
      );
    } catch (err) {
      console.error('发起流式查询失败:', err);
      updateAssistantMessage(getErrorMessage(err, '回答失败，请重试'));
      setLoading(false);
      setActiveSources(null);
      setCitationFinalized(false);
      setCitationConfidence(null);
      setInvalidCitations([]);
      setGroundedStatus(null);
      setIntentResult(null);
      setRouteResult(null);
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
    <div className="-mx-4 -mb-8 flex h-[calc(100dvh-4.5rem)] flex-col md:-mx-7 md:-mb-10 md:h-[calc(100dvh-1.75rem)] lg:-mx-9">
      <header className="flex shrink-0 items-center justify-between gap-3 border-b border-stone-200 bg-white px-4 py-3 dark:border-stone-800 dark:bg-stone-950">
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={onBack}
              className="inline-flex items-center gap-0.5 text-sm text-stone-400 transition-colors hover:text-stone-700 dark:hover:text-stone-200"
            >
              <ChevronLeft className="h-4 w-4" />
              返回
            </button>
            <span className="text-stone-300 dark:text-stone-700">/</span>
            <h1 className="truncate text-base font-semibold text-stone-900 dark:text-stone-50">知识问答</h1>
          </div>
          <p className="mt-0.5 hidden text-xs text-stone-400 sm:block">选择资料后提问，回答会标出引用来源。</p>
        </div>
        <div className="flex shrink-0 flex-wrap items-center justify-end gap-2">
          <button type="button" onClick={onUpload} className="btn-secondary inline-flex items-center gap-1.5 px-3 py-1.5 text-sm">
            <Upload className="h-3.5 w-3.5" />
            上传
          </button>
          <button
            type="button"
            onClick={() => setDebugOpen((value) => !value)}
            title="查看意图识别、问题改写和重排结果"
            className="btn-secondary inline-flex items-center gap-1.5 px-3 py-1.5 text-sm"
          >
            <Bug className="h-3.5 w-3.5" />
            <span className="hidden sm:inline">调试</span>
          </button>
          <button
            type="button"
            onClick={() => setEvalOpen(true)}
            disabled={selectedKbIds.size === 0}
            title="先选择需要评测的资料"
            className="btn-secondary inline-flex items-center gap-1.5 px-3 py-1.5 text-sm disabled:opacity-50"
          >
            <BarChart3 className="h-3.5 w-3.5" />
            <span className="hidden sm:inline">评测</span>
          </button>
        </div>
      </header>

      <div className="shrink-0 border-b border-stone-200 bg-white px-4 py-2 dark:border-stone-800 dark:bg-stone-950 lg:hidden">
        <SegmentedControl
          value={mobilePane}
          onChange={setMobilePane}
          className="w-full"
          options={[
            { value: 'history', label: '对话' },
            { value: 'chat', label: '问答' },
            { value: 'sources', label: selectedKbIds.size > 0 ? `资料 ${selectedKbIds.size}` : '资料' },
          ]}
        />
      </div>

      <div className={`grid min-h-0 flex-1 ${rightPanelOpen
        ? 'lg:grid-cols-[16.5rem_minmax(0,1fr)_17rem]'
        : 'lg:grid-cols-[16.5rem_minmax(0,1fr)_2.75rem]'}`}>
        <aside className={`${mobilePane === 'history' ? 'flex' : 'hidden'} min-h-0 flex-col border-stone-200 bg-white dark:border-stone-800 dark:bg-stone-950 lg:flex lg:border-r`}>
          <div className="flex items-center justify-between px-3 py-3">
            <h2 className="text-sm font-semibold text-stone-800 dark:text-stone-100">对话</h2>
            <button
              type="button"
              onClick={() => {
                handleNewSession();
                setMobilePane('chat');
              }}
              disabled={selectedKbIds.size === 0 || loading}
              className="rounded-lg p-1.5 text-primary-600 transition-colors hover:bg-primary-50 disabled:cursor-not-allowed disabled:opacity-40 dark:text-primary-400 dark:hover:bg-primary-950/40"
              title="新建对话"
            >
              <Plus className="h-4 w-4" />
            </button>
          </div>
          {sessionError && (
            <p className="px-3 pb-2 text-xs text-red-500" role="alert">{sessionError}</p>
          )}
          <div className="min-h-0 flex-1 overflow-y-auto px-2 pb-3">
            {loadingSessions ? (
              <LoadingState compact />
            ) : sessions.length === 0 ? (
              <EmptyState
                className="px-3 py-10 text-center"
                title="还没有对话"
                titleClassName="text-sm font-medium text-stone-500"
                description="选好资料后，从中间开始提问。"
                descriptionClassName="mt-1 text-xs text-stone-400"
              />
            ) : (
              <div className="space-y-0.5">
                {sessions.map((session) => (
                  <div
                    key={session.id}
                    onClick={() => void handleLoadSession(session.id)}
                    aria-disabled={loading}
                    className={`group rounded-lg px-2.5 py-2 transition-colors focus-within:ring-2 focus-within:ring-primary-500 ${
                      loading ? 'cursor-not-allowed opacity-60' : 'cursor-pointer'
                    } ${currentSessionId === session.id
                      ? 'bg-primary-50 dark:bg-primary-950/40'
                      : 'hover:bg-stone-100 dark:hover:bg-stone-900'
                    }`}
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
                            <Pin className="h-3 w-3 shrink-0 fill-primary-500 text-primary-500" />
                          )}
                          <p className="truncate text-sm font-medium text-stone-800 dark:text-stone-100">{session.title}</p>
                        </div>
                        <p className="mt-0.5 text-[11px] text-stone-400">
                          {session.messageCount} 条 · {formatTimeAgo(session.updatedAt)}
                        </p>
                      </button>
                      <div className="flex items-center gap-0.5 opacity-70 transition-opacity group-focus-within:opacity-100 group-hover:opacity-100 lg:opacity-0">
                        <button
                          type="button"
                          onClick={(event) => void handleTogglePin(session.id, event)}
                          disabled={loading}
                          className={`rounded p-1 transition-colors ${session.isPinned
                            ? 'text-primary-500 hover:text-primary-600'
                            : 'text-stone-400 hover:text-primary-500'
                          }`}
                          title={session.isPinned ? '取消置顶' : '置顶'}
                        >
                          <Pin className={`h-3.5 w-3.5 ${session.isPinned ? 'fill-primary-500' : ''}`} />
                        </button>
                        <button
                          type="button"
                          onClick={(event) => {
                            event.stopPropagation();
                            handleEditSessionTitle(session.id, session.title);
                          }}
                          disabled={loading}
                          className="rounded p-1 text-stone-400 transition-colors hover:text-primary-500 disabled:cursor-not-allowed"
                          title="编辑标题"
                        >
                          <Edit className="h-3.5 w-3.5" />
                        </button>
                        <button
                          type="button"
                          onClick={(event) => {
                            event.stopPropagation();
                            setSessionDeleteConfirm({ id: session.id, title: session.title });
                          }}
                          disabled={loading}
                          className="rounded p-1 text-stone-400 transition-colors hover:text-red-500 disabled:cursor-not-allowed"
                          title="删除"
                        >
                          <Trash2 className="h-3.5 w-3.5" />
                        </button>
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        </aside>

        <section className={`${mobilePane === 'chat' ? 'flex' : 'hidden'} min-h-0 min-w-0 flex-col bg-stone-50 dark:bg-stone-950 lg:flex`}>
          {showChatPane ? (
            <>
              <div className="border-b border-stone-200 bg-white px-4 py-3 dark:border-stone-800 dark:bg-stone-950">
                <h2 className="truncate text-sm font-semibold text-stone-900 dark:text-stone-50">{sessionHeading}</h2>
                <div className="mt-2 flex flex-wrap gap-1.5">
                  {selectedKnowledgeBases.map((kb) => (
                    <span
                      key={kb.id}
                      className="rounded-full bg-primary-50 px-2 py-0.5 text-[11px] text-primary-700 dark:bg-primary-950/40 dark:text-primary-300"
                    >
                      {kb.name}
                    </span>
                  ))}
                </div>
                {missingQueryableMaterials && (
                  <div className="mt-3 flex flex-wrap items-center gap-2 rounded-lg bg-amber-50 px-3 py-2 dark:bg-amber-950/30">
                    <p className="text-xs text-amber-800 dark:text-amber-200">本次对话没有可提问的资料，请在右侧勾选向量化完成的知识库。</p>
                    <button
                      type="button"
                      onClick={() => {
                        setRightPanelOpen(true);
                        setMobilePane('sources');
                      }}
                      className="rounded-md bg-amber-500 px-2.5 py-1 text-xs text-white hover:bg-amber-600"
                    >
                      去选资料
                    </button>
                  </div>
                )}
                {kbNeedsSync && (
                  <div className="mt-3 flex flex-wrap items-center gap-2 rounded-lg bg-amber-50 px-3 py-2 dark:bg-amber-950/30">
                    <p className="text-xs text-amber-800 dark:text-amber-200">本次对话使用的资料已更改</p>
                    <button
                      type="button"
                      disabled={syncingKb}
                      onClick={() => void handleSyncKnowledgeBases()}
                      className="rounded-md bg-amber-500 px-2.5 py-1 text-xs text-white hover:bg-amber-600 disabled:opacity-50"
                    >
                      {syncingKb ? '更新中...' : '更新本次对话'}
                    </button>
                  </div>
                )}
              </div>

              <div className="relative min-h-0 flex-1">
                {messages.length === 0 ? (
                  <div className="absolute inset-0 flex flex-col items-center justify-center px-6 text-center">
                    <div className="mb-4 flex h-12 w-12 items-center justify-center rounded-2xl bg-primary-50 text-primary-600 dark:bg-primary-950/50 dark:text-primary-300">
                      <Sparkles className="h-6 w-6" />
                    </div>
                    <p className="text-sm font-medium text-stone-700 dark:text-stone-200">
                      {missingQueryableMaterials ? '先补选要问的资料' : '从一份资料开始提问'}
                    </p>
                    <p className="mt-1 max-w-sm text-xs leading-5 text-stone-400">
                      {missingQueryableMaterials
                        ? '历史对话还在。勾选向量化完成的知识库后即可继续提问。'
                        : '回答会带上引用。也可以先点下面的示例。'}
                    </p>
                    {!missingQueryableMaterials && (
                    <div className="mt-5 flex max-w-lg flex-wrap justify-center gap-2">
                      {SUGGESTED_PROMPTS.map((prompt) => (
                        <button
                          key={prompt}
                          type="button"
                          disabled={loading}
                          onClick={() => void submitQuestion(prompt)}
                          className="rounded-full border border-stone-200 bg-white px-3 py-1.5 text-left text-xs text-stone-600 transition-colors hover:border-primary-300 hover:text-primary-700 disabled:opacity-50 dark:border-stone-700 dark:bg-stone-900 dark:text-stone-300 dark:hover:border-primary-800"
                        >
                          {prompt}
                        </button>
                      ))}
                    </div>
                    )}
                  </div>
                ) : (
                  <Virtuoso
                    ref={virtuosoRef}
                    data={messages}
                    initialTopMostItemIndex={messages.length - 1}
                    followOutput="smooth"
                    className="h-full w-full"
                    itemContent={(index, msg) => (
                      <div className="px-4 pb-5 first:pt-5">
                        <div className={`flex ${msg.type === 'user' ? 'justify-end' : 'justify-start'}`}>
                          <div className={`max-w-[min(42rem,88%)] ${msg.type === 'user' ? 'items-end' : 'items-start'} flex flex-col`}>
                            <div
                              className={`rounded-2xl px-4 py-3 ${msg.type === 'user'
                                ? 'rounded-br-md bg-primary-600 text-white'
                                : 'rounded-bl-md border border-stone-200/80 bg-white text-stone-800 dark:border-stone-800 dark:bg-stone-900 dark:text-stone-100'
                              }`}
                            >
                              {msg.type === 'user' ? (
                                <p className="whitespace-pre-wrap text-sm leading-relaxed">{msg.content}</p>
                              ) : (
                                <>
                                  <ChatMarkdown content={msg.content} />
                                  {loading && index === messages.length - 1 && (
                                    <span className="ml-1 inline-block h-5 w-0.5 animate-pulse bg-primary-500" />
                                  )}
                                  {loading && index === messages.length - 1 && cardMessage && (
                                    <div className="mt-2 rounded-lg bg-amber-50 px-3 py-2 text-sm text-amber-800 dark:bg-amber-950/30 dark:text-amber-200">
                                      {cardMessage}
                                    </div>
                                  )}
                                  {index === messages.length - 1 && cardChoices.length > 0 && (
                                    <div className="mt-2 flex flex-wrap gap-2">
                                      {cardChoices.map((choice) => (
                                        <button
                                          key={choice.id}
                                          type="button"
                                          disabled={loading}
                                          className="rounded-full border border-primary-200 px-3 py-1.5 text-xs text-primary-700 hover:bg-primary-50 disabled:opacity-50 dark:border-primary-800 dark:text-primary-300 dark:hover:bg-primary-950/40"
                                          onClick={() => {
                                            void submitQuestion(buildRagCardFollowUp(choice));
                                          }}
                                        >
                                          {choice.label}
                                        </button>
                                      ))}
                                    </div>
                                  )}
                                  {index === messages.length - 1 && (
                                    <AssistantTrace
                                      intentResult={intentResult}
                                      routeResult={routeResult}
                                      rewrittenQuestion={rewrittenQuestion}
                                      progressText={progressText}
                                      loading={loading}
                                      activeSources={activeSources}
                                      citationFinalized={citationFinalized}
                                      citationConfidence={citationConfidence}
                                      invalidCitations={invalidCitations}
                                      groundedStatus={groundedStatus}
                                    />
                                  )}
                                </>
                              )}
                            </div>
                            <p className="mt-1 px-1 text-[11px] text-stone-400">{formatMessageTime(msg.timestamp)}</p>
                          </div>
                        </div>
                      </div>
                    )}
                  />
                )}
              </div>

              <div className="border-t border-stone-200 bg-white px-4 py-3 dark:border-stone-800 dark:bg-stone-950">
                <div className="rounded-xl border border-stone-200 bg-stone-50/80 p-2 focus-within:border-primary-400 focus-within:ring-2 focus-within:ring-primary-500/15 dark:border-stone-700 dark:bg-stone-900 dark:focus-within:border-primary-500">
                  <textarea
                    ref={composerRef}
                    value={question}
                    onChange={(event) => {
                      setQuestion(event.target.value);
                      resizeComposer(event.target.value);
                    }}
                    onKeyDown={(event) => {
                      if (event.key === 'Enter' && !event.shiftKey) {
                        event.preventDefault();
                        void submitQuestion();
                      }
                    }}
                    placeholder={missingQueryableMaterials
                      ? '先勾选资料再提问'
                      : '问一个具体问题，Enter 发送，Shift+Enter 换行'}
                    rows={1}
                    className="max-h-40 min-h-11 w-full resize-none bg-transparent px-2 py-2 text-sm text-stone-900 outline-none placeholder:text-stone-400 dark:text-stone-100"
                    disabled={loading || missingQueryableMaterials}
                  />
                  <div className="flex items-center justify-between gap-2 px-1 pb-0.5">
                    <p className="text-[11px] text-stone-400">
                      {missingQueryableMaterials ? '尚未选择可提问的资料' : `已选 ${selectedKbIds.size} 份资料`}
                    </p>
                    <button
                      type="button"
                      onClick={() => void submitQuestion()}
                      disabled={!question.trim() || selectedKbIds.size === 0 || loading}
                      className="btn-primary inline-flex items-center gap-1.5 px-3 py-1.5 text-sm disabled:cursor-not-allowed disabled:opacity-50"
                    >
                      <Send className="h-3.5 w-3.5" />
                      发送
                    </button>
                  </div>
                </div>
              </div>
            </>
          ) : (
            <div className="flex flex-1 flex-col items-center justify-center px-6 text-center text-stone-400">
              <div className="mb-4 flex h-12 w-12 items-center justify-center rounded-2xl bg-stone-100 text-stone-400 dark:bg-stone-900">
                <MessageSquare className="h-6 w-6" />
              </div>
              <p className="text-sm font-medium text-stone-600 dark:text-stone-300">先选择要问的资料</p>
              <p className="mt-1 text-xs text-stone-400">右侧勾选向量化完成的知识库，再回到这里提问。</p>
              <button
                type="button"
                onClick={() => {
                  setRightPanelOpen(true);
                  setMobilePane('sources');
                }}
                className="btn-secondary mt-4 px-3 py-1.5 text-sm"
              >
                去选资料
              </button>
            </div>
          )}
        </section>

        <aside className={`${mobilePane === 'sources' ? 'flex' : 'hidden'} min-h-0 min-w-0 flex-col border-stone-200 bg-white dark:border-stone-800 dark:bg-stone-950 ${rightPanelOpen ? 'lg:flex lg:border-l' : 'lg:hidden'}`}>
            <div className="flex items-center justify-between px-3 py-3">
              <h2 className="text-sm font-semibold text-stone-800 dark:text-stone-100">
                选择资料
                {selectedKbIds.size > 0 && (
                  <span className="ml-1.5 text-xs font-normal text-stone-400">{selectedKbIds.size}</span>
                )}
              </h2>
              <button
                type="button"
                onClick={() => setRightPanelOpen(false)}
                className="hidden rounded p-1 text-stone-400 hover:text-stone-600 dark:hover:text-stone-200 lg:inline-flex"
                aria-label="收起资料面板"
              >
                <ChevronLeft className="h-4 w-4" />
              </button>
            </div>

            <div className="px-3 pb-3">
              <div className="flex items-center gap-2 rounded-lg border border-stone-200 bg-stone-50 px-2.5 py-1.5 focus-within:border-primary-400 focus-within:ring-2 focus-within:ring-primary-500/15 dark:border-stone-700 dark:bg-stone-900">
                <Search className="h-3.5 w-3.5 shrink-0 text-stone-400" />
                <input
                  type="text"
                  value={searchKeyword}
                  onChange={(event) => setSearchKeyword(event.target.value)}
                  onKeyDown={(event) => event.key === 'Enter' && void handleSearch()}
                  placeholder="搜索资料"
                  className="w-full bg-transparent text-sm text-stone-900 outline-none placeholder:text-stone-400 dark:text-white"
                />
              </div>
              <div className="mt-2">
                <KnowledgeBaseSortSelect
                  value={sortBy}
                  onChange={(value) => {
                    setSortBy(value);
                    setSearchKeyword('');
                  }}
                  compact
                />
              </div>
            </div>

            <div className="min-h-0 flex-1 overflow-y-auto px-2 pb-3">
              {loadingList ? (
                <LoadingState compact />
              ) : knowledgeBases.length === 0 ? (
                <EmptyState
                  className="px-3 py-8 text-center"
                  title={searchKeyword ? '没有匹配的资料' : '还没有可提问的资料'}
                  titleClassName="mb-2 text-sm font-medium text-stone-500"
                  description={searchKeyword ? undefined : '上传并完成向量化后会出现在这里。'}
                  descriptionClassName="mb-3 text-xs text-stone-400"
                  action={!searchKeyword && (
                    <button
                      type="button"
                      onClick={onUpload}
                      className="text-sm font-medium text-primary-600 hover:text-primary-700"
                    >
                      去上传
                    </button>
                  )}
                />
              ) : (
                <div className="space-y-2">
                  {groupedKnowledgeBases.map((group) => (
                    <div key={group.name} className="overflow-hidden rounded-lg border border-stone-200/80 dark:border-stone-800">
                      <button
                        type="button"
                        onClick={() => toggleCategory(group.name)}
                        aria-expanded={group.isExpanded}
                        className="flex w-full items-center justify-between bg-stone-50 px-3 py-2 transition-colors hover:bg-stone-100 dark:bg-stone-900 dark:hover:bg-stone-800"
                      >
                        <div className="flex items-center gap-2">
                          <ChevronRight
                            className={`h-3.5 w-3.5 text-stone-400 transition-transform ${group.isExpanded ? 'rotate-90' : ''}`}
                          />
                          <span className="text-sm font-medium text-stone-700 dark:text-stone-300">{group.name}</span>
                        </div>
                        <span className="text-[11px] text-stone-400">{group.items.length}</span>
                      </button>
                      {group.isExpanded && (
                        <div className="space-y-1 p-1.5">
                          {group.items.map((kb) => (
                            <label
                              key={kb.id}
                              className={`block cursor-pointer rounded-lg px-2 py-2 transition-colors focus-within:ring-2 focus-within:ring-primary-500 ${
                                selectedKbIds.has(kb.id)
                                  ? 'bg-primary-50 dark:bg-primary-950/40'
                                  : 'hover:bg-stone-50 dark:hover:bg-stone-900'
                              }`}
                            >
                              <div className="flex items-center gap-2">
                                <input
                                  type="checkbox"
                                  checked={selectedKbIds.has(kb.id)}
                                  onChange={() => handleToggleKb(kb.id)}
                                  className="h-3.5 w-3.5 rounded text-primary-500 focus:ring-primary-500"
                                />
                                <span className="flex-1 truncate text-xs font-medium text-stone-800 dark:text-stone-100">{kb.name}</span>
                              </div>
                              <p className="ml-5 mt-0.5 text-[11px] text-stone-400">{formatFileSize(kb.fileSize)}</p>
                            </label>
                          ))}
                        </div>
                      )}
                    </div>
                  ))}
                </div>
              )}
            </div>
          </aside>

        {!rightPanelOpen && (
          <button
            type="button"
            onClick={() => setRightPanelOpen(true)}
            className="hidden items-center justify-center border-l border-stone-200 bg-white text-stone-400 transition-colors hover:bg-stone-50 hover:text-stone-600 dark:border-stone-800 dark:bg-stone-950 dark:hover:bg-stone-900 lg:flex"
            title="展开资料面板"
          >
            <ChevronRight className="h-5 w-5" />
          </button>
        )}
      </div>

      <DeleteConfirmDialog
        open={!!sessionDeleteConfirm}
        item={sessionDeleteConfirm ? { title: sessionDeleteConfirm.title } : null}
        itemType="对话"
        onConfirm={handleDeleteSession}
        onCancel={() => setSessionDeleteConfirm(null)}
      />

      {editingSessionTitle && (
        <>
          <div
            onClick={() => {
              setEditingSessionTitle(null);
              setNewSessionTitle('');
            }}
            className="fixed inset-0 z-50 bg-black/50"
          />
          <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
            <div onClick={(event) => event.stopPropagation()} className="surface-card w-full max-w-md p-5">
              <h3 className="mb-4 text-lg font-semibold text-stone-900 dark:text-white">编辑标题</h3>
              <input
                type="text"
                value={newSessionTitle}
                onChange={(event) => setNewSessionTitle(event.target.value)}
                onKeyDown={(event) => event.key === 'Enter' && void handleSaveSessionTitle()}
                placeholder="请输入新标题"
                className="dark-input mb-4 w-full px-3 py-2.5 text-sm"
                autoFocus
              />
              <div className="flex justify-end gap-3">
                <button
                  type="button"
                  onClick={() => {
                    setEditingSessionTitle(null);
                    setNewSessionTitle('');
                  }}
                  className="px-4 py-2 text-sm text-stone-500 hover:text-stone-800 dark:text-stone-400 dark:hover:text-white"
                >
                  取消
                </button>
                <button
                  type="button"
                  onClick={() => void handleSaveSessionTitle()}
                  disabled={!newSessionTitle.trim()}
                  className="btn-primary px-4 py-2 text-sm disabled:opacity-50"
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
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
          onClick={() => setEvalOpen(false)}
        >
          <div onClick={(event) => event.stopPropagation()} className="surface-card w-full max-w-2xl p-5">
            <div className="mb-1 flex items-center justify-between">
              <h3 className="flex items-center gap-2 text-lg font-semibold text-stone-900 dark:text-white">
                <BarChart3 className="h-5 w-5 text-primary-500" />
                检索评测
              </h3>
              <button
                type="button"
                onClick={() => setEvalOpen(false)}
                className="rounded-lg p-1.5 text-stone-400 hover:bg-stone-100 hover:text-stone-600 dark:hover:bg-stone-800 dark:hover:text-stone-200"
                aria-label="关闭检索评测"
              >
                <X className="h-5 w-5" />
              </button>
            </div>
            <p className="mb-4 text-xs leading-relaxed text-stone-400">
              用 JSON 用例检查所选资料的检索质量，并对比 Hit@K、MRR 和 NDCG 等指标。
            </p>
            <textarea
              value={evalInput}
              onChange={(event) => setEvalInput(event.target.value)}
              placeholder='[{"question":"JVM GC 是什么？","expectedKeywords":["垃圾回收"],"expectedChunkIds":[]}]'
              className="dark-input h-44 w-full px-3 py-2.5 font-mono text-sm"
            />
            {evalError && (
              <p className="mt-2 text-sm text-red-500">{evalError}</p>
            )}
            {evalResult && (
              <>
                <div className="my-4 grid grid-cols-5 gap-2 text-center">
                  {[
                    ['Hit@K', evalResult.hitRate],
                    ['MRR', evalResult.mrr],
                    ['NDCG', evalResult.ndcg],
                    ['检索召回', evalResult.retrievalRecall],
                    ['检索精确', evalResult.retrievalPrecision],
                  ].map(([label, value]) => (
                    <div key={label} className="rounded-lg bg-stone-50 p-3 dark:bg-stone-800">
                      <p className="text-xs text-stone-500 dark:text-stone-400">{label}</p>
                      <p className="text-lg font-semibold text-stone-900 dark:text-white">
                        {Number(value).toFixed(2)}
                      </p>
                    </div>
                  ))}
                </div>
                <div className="max-h-72 space-y-3 overflow-y-auto pr-1">
                  {evalResult.items.map((item, index) => (
                    <div key={`${item.question}-${index}`} className="rounded-lg border border-stone-200 p-3 dark:border-stone-800">
                      <div className="flex items-center justify-between gap-3">
                        <p className="truncate text-sm font-medium text-stone-800 dark:text-white">{item.question}</p>
                        <span className={`text-xs font-medium ${item.hit ? 'text-emerald-600' : 'text-red-500'}`}>
                          {item.hit ? `命中 #${item.firstHitRank}` : '未命中'}
                        </span>
                      </div>
                      <div className="mt-2 space-y-2">
                        {item.retrievedSegments.slice(0, evalResult.k).map((segment) => (
                          <div key={`${segment.rank}-${segment.chunkId}`} className="rounded bg-stone-50 p-2 dark:bg-stone-800/60">
                            <p className="text-xs text-stone-500 dark:text-stone-400">
                              #{segment.rank} chunk={segment.chunkId || '-'} doc={segment.docId ?? '-'} score={segment.score ?? '-'}
                            </p>
                            <p className="line-clamp-2 text-xs text-stone-700 dark:text-stone-200">{segment.snippet}</p>
                          </div>
                        ))}
                      </div>
                    </div>
                  ))}
                </div>
              </>
            )}
            <div className="mt-4 flex justify-end gap-3">
              <button
                type="button"
                onClick={() => setEvalOpen(false)}
                className="px-4 py-2 text-sm text-stone-500 hover:text-stone-800 dark:text-stone-400 dark:hover:text-white"
              >
                关闭
              </button>
              <button
                type="button"
                onClick={() => void handleExportQa()}
                disabled={exportLoading || !evalInput.trim()}
                className="btn-secondary px-4 py-2 text-sm disabled:opacity-50"
              >
                {exportLoading ? '导出中...' : 'RAGAS 导出'}
              </button>
              <button
                type="button"
                onClick={() => void handleRunEval()}
                disabled={evalLoading || !evalInput.trim()}
                className="btn-primary px-4 py-2 text-sm disabled:opacity-50"
              >
                {evalLoading ? '评测中...' : '开始评测'}
              </button>
            </div>
            {exportResult && (
              <p className="mt-3 text-xs text-emerald-600 dark:text-emerald-400">
                已导出 {exportResult.total} 条 QA 样本，可用于 eval/ragas。
              </p>
            )}
            <div className="mt-4 border-t border-stone-200 pt-4 dark:border-stone-800">
              <p className="mb-2 text-sm font-medium text-stone-700 dark:text-stone-200">单题样本生成</p>
              <div className="flex gap-2">
                <input
                  type="text"
                  value={datasetQuestion}
                  onChange={(event) => setDatasetQuestion(event.target.value)}
                  placeholder="输入一个问题生成评测样本"
                  className="dark-input flex-1 px-3 py-2 text-sm"
                />
                <button
                  type="button"
                  disabled={datasetLoading || !datasetQuestion.trim()}
                  onClick={() => void handleGenerateDataset()}
                  className="btn-secondary px-3 py-2 text-sm disabled:opacity-50"
                >
                  {datasetLoading ? '生成中...' : '生成'}
                </button>
              </div>
              {datasetResult && (
                <div className="mt-2 max-h-32 overflow-y-auto whitespace-pre-wrap rounded-lg bg-stone-50 p-3 text-xs text-stone-700 dark:bg-stone-800/60 dark:text-stone-200">
                  {datasetResult.answer}
                </div>
              )}
            </div>
          </div>
        </div>
      )}

      {debugOpen && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
          onClick={() => setDebugOpen(false)}
        >
          <div onClick={(event) => event.stopPropagation()} className="surface-card w-full max-w-2xl p-5">
            <div className="mb-1 flex items-center justify-between">
              <h3 className="flex items-center gap-2 text-lg font-semibold text-stone-900 dark:text-white">
                <Bug className="h-5 w-5 text-primary-500" />
                检索链路调试
              </h3>
              <button
                type="button"
                onClick={() => setDebugOpen(false)}
                className="rounded-lg p-1.5 text-stone-400 hover:bg-stone-100 hover:text-stone-600 dark:hover:bg-stone-800 dark:hover:text-stone-200"
                aria-label="关闭链路调试"
              >
                <X className="h-5 w-5" />
              </button>
            </div>
            <p className="mb-4 text-xs leading-relaxed text-stone-400">
              输入一个问题，分别查看意图识别、提示词组装、问题改写和重排结果。
            </p>
            <input
              type="text"
              value={debugQuestion}
              onChange={(event) => setDebugQuestion(event.target.value)}
              placeholder="输入测试问题"
              className="dark-input mb-3 w-full px-3 py-2.5 text-sm"
            />
            <div className="mb-4 flex flex-wrap gap-2">
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
                  className="btn-secondary px-3 py-1.5 text-sm disabled:opacity-50"
                >
                  {label}
                </button>
              ))}
            </div>
            <pre className="max-h-64 overflow-y-auto whitespace-pre-wrap rounded-lg bg-stone-50 p-3 text-xs text-stone-700 dark:bg-stone-900/60 dark:text-stone-200">
              {debugLoading ? '请求中...' : (debugOutput || '选择上方模块运行调试')}
            </pre>
          </div>
        </div>
      )}
    </div>
  );
}
