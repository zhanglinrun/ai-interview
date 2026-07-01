import { AI_REQUEST_TIMEOUT_MS, getAuthHeaders, request } from './request';
import { API_BASE_URL, fetchTextStream } from './stream';

// 文档状态机（对齐后端 DocumentStatus）
// INIT/UPLOADED：待处理；CONVERTING/CONVERTED/CHUNKED：处理中；VECTOR_STORED/STORED：已完成
export type DocStatus =
  | 'INIT'
  | 'UPLOADED'
  | 'CONVERTING'
  | 'CONVERTED'
  | 'CHUNKED'
  | 'VECTOR_STORED'
  | 'STORED';

export type KnowledgeBaseType = 'DOCUMENT_SEARCH' | 'DATA_QUERY';

export interface KnowledgeBaseItem {
  id: number;
  name: string;
  category: string | null;
  originalFilename: string;
  fileSize: number;
  contentType: string;
  uploadedAt: string;
  lastAccessedAt: string;
  accessCount: number;
  questionCount: number;
  docStatus: DocStatus;
  currentVersionId: number | null;
  dataTableName: string | null;
  dataRowCount: number | null;
  knowledgeBaseType: KnowledgeBaseType | null;
}

// 统计信息
export interface KnowledgeBaseStats {
  totalCount: number;
  totalQuestionCount: number;
  totalAccessCount: number;
  completedCount: number;
  processingCount: number;
}

export type SortOption = 'time' | 'size' | 'access' | 'question';

export interface UploadKnowledgeBaseResponse {
  knowledgeBase: {
    id: number;
    name: string;
    category: string;
    fileSize: number;
    contentLength: number;
    docStatus: DocStatus;
  };
  storage: {
    fileKey: string;
    fileUrl: string;
  };
  duplicate: boolean;
}

// 知识库版本（对齐后端 KnowledgeBaseVersionDTO）
export interface KnowledgeBaseVersion {
  versionId: number;
  version: string;
  status: DocStatus;
  uploadUser: string | null;
  changelog: string | null;
  createdAt: string;
}

export interface QueryRequest {
  knowledgeBaseIds: number[];  // 支持多个知识库
  question: string;
}

export interface RagSource {
  knowledgeBaseId: number | null;
  documentTitle: string;
  sourceName: string | null;
  category: string | null;
  sectionTitle: string | null;
  chunkIndex: number | null;
  chunkCount: number | null;
  snippet: string;
  similarity: number | null;
}

export interface QueryResponse {
  answer: string;
  knowledgeBaseId: number;
  knowledgeBaseName: string;
  sources: RagSource[];
}

export interface RagEvalRequestItem {
  question: string;
  expectedKeywords: string[];
  expectedChunkIds: string[];
}

export interface RagEvalRequest {
  knowledgeBaseIds: number[];
  items: RagEvalRequestItem[];
  k?: number;
  title?: string;
}

export interface RagEvalResponse {
  runId: string;
  total: number;
  k: number;
  hitRate: number;
  mrr: number;
  ndcg: number;
  citationHitRate: number;
  citationCoverage: number;
  items: RagEvalItemResult[];
}

export interface RagEvalItemResult {
  question: string;
  hit: boolean;
  firstHitRank: number;
  reciprocalRank: number;
  ndcg: number;
  citationHitRate: number;
  citationCoverage: number;
  retrievedChunkIds: string[];
  retrievedSegments: RagEvalRetrievedSegment[];
}

export interface RagEvalRetrievedSegment {
  rank: number;
  chunkId: string | null;
  docId: number | null;
  snippet: string;
  score: number | null;
}

export interface DataTablePreview {
  tableName: string;
  logicalName: string;
  total: number;
  page: number;
  size: number;
  columns: Array<{ name: string; title: string }>;
  rows: Array<Record<string, unknown>>;
}

export interface RagQueryTrace {
  traceId: string;
  question: string;
  rewrittenQuestion: string | null;
  routeStrategy: string | null;
  routeReasoning: string | null;
  retrievedJson: string | null;
  rerankedJson: string | null;
  finalSourcesJson: string | null;
  answer: string | null;
  confidence: number | null;
  invalidCitationsJson: string | null;
  latencyMs: number | null;
  createdAt: string;
}

function extractDataLineContent(line: string): string | null {
  if (!line.startsWith('data:')) {
    return null;
  }
  let content = line.substring(5);
  if (content.startsWith(' ')) {
    content = content.substring(1);
  }
  return content.length === 0 ? '\n' : content;
}

function processLineStreamBuffer(
  buffer: string,
  emit: (chunk: string) => void,
  isFinal: boolean
): string {
  const lines = buffer.split('\n');
  const remaining = isFinal ? '' : lines.pop() || '';

  for (const line of lines) {
    const content = extractDataLineContent(line);
    if (content !== null) {
      emit(content);
    }
  }

  if (isFinal && remaining) {
    const content = extractDataLineContent(remaining);
    if (content) {
      emit(content);
    }
  }

  return remaining;
}

export const knowledgeBaseApi = {
  /**
   * 上传知识库文件
   */
  async uploadKnowledgeBase(
    file: File,
    name?: string,
    category?: string,
    knowledgeBaseType: KnowledgeBaseType = 'DOCUMENT_SEARCH',
  ): Promise<UploadKnowledgeBaseResponse> {
    const formData = new FormData();
    formData.append('file', file);
    if (name) {
      formData.append('name', name);
    }
    if (category) {
      formData.append('category', category);
    }
    formData.append('knowledgeBaseType', knowledgeBaseType);
    return request.upload<UploadKnowledgeBaseResponse>('/api/knowledgebase/upload', formData);
  },

  async splitDocument(
    id: number,
    splitParam?: {
      splitType: string;
      chunkSize?: number;
      overlap?: number;
      titleLevel?: number;
      separator?: string;
      regex?: string;
    },
  ): Promise<{ segmentCount: number }> {
    return request.post<{ segmentCount: number }>(`/api/knowledgebase/${id}/split`, splitParam ?? {});
  },

    /**
     * 下载知识库文件
     */
    async downloadKnowledgeBase(id: number): Promise<Blob> {
        return request.getBlob(`/api/knowledgebase/${id}/download`);
    },

  /**
   * 获取所有知识库列表
   */
  async getAllKnowledgeBases(sortBy?: SortOption, docStatus?: DocStatus): Promise<KnowledgeBaseItem[]> {
    const params = new URLSearchParams();
    if (sortBy) {
      params.append('sortBy', sortBy);
    }
    if (docStatus) {
      params.append('docStatus', docStatus);
    }
    const queryString = params.toString();
    return request.get<KnowledgeBaseItem[]>(`/api/knowledgebase/list${queryString ? `?${queryString}` : ''}`);
  },

  /**
   * 获取知识库详情
   */
  async getKnowledgeBase(id: number): Promise<KnowledgeBaseItem> {
    return request.get<KnowledgeBaseItem>(`/api/knowledgebase/${id}`);
  },

  /**
   * 删除知识库
   */
  async deleteKnowledgeBase(id: number): Promise<void> {
    return request.delete(`/api/knowledgebase/${id}`);
  },

  // ========== 分类管理 ==========

  /**
   * 获取所有分类
   */
  async getAllCategories(): Promise<string[]> {
    return request.get<string[]>('/api/knowledgebase/categories');
  },

  /**
   * 根据分类获取知识库
   */
  async getByCategory(category: string): Promise<KnowledgeBaseItem[]> {
    return request.get<KnowledgeBaseItem[]>(`/api/knowledgebase/category/${encodeURIComponent(category)}`);
  },

  /**
   * 获取未分类的知识库
   */
  async getUncategorized(): Promise<KnowledgeBaseItem[]> {
    return request.get<KnowledgeBaseItem[]>('/api/knowledgebase/uncategorized');
  },

  /**
   * 更新知识库分类
   */
  async updateCategory(id: number, category: string | null): Promise<void> {
    return request.put(`/api/knowledgebase/${id}/category`, { category });
  },

  // ========== 搜索 ==========

  /**
   * 搜索知识库
   */
  async search(keyword: string): Promise<KnowledgeBaseItem[]> {
    return request.get<KnowledgeBaseItem[]>(`/api/knowledgebase/search?keyword=${encodeURIComponent(keyword)}`);
  },

  // ========== 统计 ==========

  /**
   * 获取知识库统计信息
   */
  async getStatistics(): Promise<KnowledgeBaseStats> {
    return request.get<KnowledgeBaseStats>('/api/knowledgebase/stats');
  },

  // ========== 向量化管理 ==========

  /**
   * 重新向量化知识库（手动重试）
   */
  async revectorize(id: number): Promise<void> {
    return request.post(`/api/knowledgebase/${id}/revectorize`);
  },

  async previewDataTable(id: number, page = 1, size = 50): Promise<DataTablePreview> {
    return request.get<DataTablePreview>(`/api/knowledgebase/${id}/data/preview?page=${page}&size=${size}`);
  },

  async evaluateRetrieval(req: RagEvalRequest): Promise<RagEvalResponse> {
    return request.post<RagEvalResponse>('/api/knowledgebase/evaluate-retrieval', req, {
      timeout: AI_REQUEST_TIMEOUT_MS,
    });
  },

  async listTraces(limit = 20): Promise<RagQueryTrace[]> {
    return request.get<RagQueryTrace[]>(`/api/knowledgebase/traces?limit=${limit}`);
  },

  // ========== 版本管理 ==========

  /**
   * 查询知识库所有版本（降序，最新在前）
   */
  async listVersions(id: number): Promise<KnowledgeBaseVersion[]> {
    return request.get<KnowledgeBaseVersion[]>(`/api/knowledgebase/${id}/versions`);
  },

  /**
   * 切换当前激活版本（已向量化版本零重建热切换，未向量化版本先激活再切换）
   */
  async switchVersion(id: number, versionId: number): Promise<void> {
    return request.post(`/api/knowledgebase/${id}/versions/${versionId}/switch`);
  },

  /**
   * 基于知识库回答问题
   */
  async queryKnowledgeBase(req: QueryRequest): Promise<QueryResponse> {
    return request.post<QueryResponse>('/api/knowledgebase/query', req, {
      timeout: AI_REQUEST_TIMEOUT_MS, // 3分钟超时
    });
  },

  /**
   * 基于知识库回答问题（流式SSE）
   * 注意：SSE 使用 fetch API，不走统一的 axios 封装
   */
  async queryKnowledgeBaseStream(
    req: QueryRequest,
    onMessage: (chunk: string) => void,
    onComplete: () => void,
    onError: (error: Error) => void
  ): Promise<void> {
    return fetchTextStream({
      url: `${API_BASE_URL}/api/knowledgebase/query/stream`,
      init: {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          ...getAuthHeaders(),
        },
        body: JSON.stringify(req),
      },
      onMessage,
      onComplete,
      onError,
      processBuffer: processLineStreamBuffer,
    });
  },
};
