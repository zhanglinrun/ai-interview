import { getAuthHeaders, request } from './request';
import { API_BASE_URL, fetchTextStream } from './stream';

// ========== 类型定义 ==========

export interface RagChatSession {
  id: number;
  title: string;
  knowledgeBaseIds: number[];
  createdAt: string;
}

export interface RagChatSessionListItem {
  id: number;
  title: string;
  messageCount: number;
  knowledgeBaseNames: string[];
  updatedAt: string;
  isPinned: boolean;
}

export interface RagChatMessage {
  id: number;
  type: 'user' | 'assistant';
  content: string;
  createdAt: string;
}

export interface RagChatKnowledgeBaseItem {
  id: number;
  name: string;
  originalFilename: string;
  fileSize: number;
  contentType: string;
  uploadedAt: string;
  lastAccessedAt: string;
  accessCount: number;
  questionCount: number;
}

export interface RagChatSessionDetail {
  id: number;
  title: string;
  knowledgeBases: RagChatKnowledgeBaseItem[];
  messages: RagChatMessage[];
  createdAt: string;
  updatedAt: string;
}

const PROGRESS_PREFIX = 'progress:';
const REFERENCE_PREFIX = 'reference:';
const CITATION_PREFIX = 'citation:';
const REWRITTEN_PREFIX = 'rewritten:';
const INTENT_PREFIX = 'intent:';
const CARD_PREFIX = 'card:';
const CARD_CHOICE_PREFIX = 'card_choice:';

export interface RagCardChoice {
  id: string;
  label: string;
  type: string;
}

export interface IntentStrategyScore {
  strategy: string;
  intent: string;
  confidence: number;
  weight: number;
  weightedScore: number;
  reason: string;
}

export interface IntentStreamResult {
  reason: string;
  related: boolean;
  intent: string;
  confidence: number | null;
  strategies: IntentStrategyScore[];
  cached?: boolean | null;
}

/**
 * progress:/reference:/citation:/rewritten:/intent:/card: 前缀事件是 RAG 元数据（不进回答正文），
 * 其内容需原样保留（reference 内是 JSON，不能做 \\n→\n 转义，否则破坏 JSON）。
 */
function isPrefixedEvent(content: string): boolean {
  return content.startsWith(PROGRESS_PREFIX)
    || content.startsWith(REFERENCE_PREFIX)
    || content.startsWith(CITATION_PREFIX)
    || content.startsWith(REWRITTEN_PREFIX)
    || content.startsWith(INTENT_PREFIX)
    || content.startsWith(CARD_PREFIX)
    || content.startsWith(CARD_CHOICE_PREFIX);
}

function extractEventContent(event: string): string | null {
  if (!event.trim()) {
    return null;
  }

  const lines = event.split('\n');
  const contentParts: string[] = [];

  for (const line of lines) {
    if (line.startsWith('data:')) {
      contentParts.push(line.substring(5));
    }
  }

  if (contentParts.length === 0) {
    return null;
  }

  const joined = contentParts.join('');
  // 前缀事件原样返回，不做 \\n→\n 转义（reference 内是 JSON）
  if (isPrefixedEvent(joined)) {
    return joined;
  }
  return joined
    .replace(/\\n/g, '\n')
    .replace(/\\r/g, '\r');
}

function processEventStreamBuffer(
  buffer: string,
  emit: (chunk: string) => void,
  isFinal: boolean
): string {
  if (isFinal) {
    const content = extractEventContent(buffer);
    if (content) {
      emit(content);
    }
    return '';
  }

  let newlineIndex = buffer.indexOf('\n\n');
  if (newlineIndex === -1) {
    const singleLineIndex = buffer.indexOf('\n');
    if (singleLineIndex !== -1 && buffer.substring(0, singleLineIndex).startsWith('data:')) {
      const line = buffer.substring(0, singleLineIndex);
      const content = extractEventContent(line);
      if (content) {
        emit(content);
      }
      return buffer.substring(singleLineIndex + 1);
    }
    return buffer;
  }

  while (newlineIndex !== -1) {
    const eventBlock = buffer.substring(0, newlineIndex);
    const content = extractEventContent(eventBlock);
    if (content !== null) {
      emit(content);
    }
    buffer = buffer.substring(newlineIndex + 2);
    newlineIndex = buffer.indexOf('\n\n');
  }

  return buffer;
}

// ========== API 函数 ==========

/**
 * SSE 流事件类型：
 * - token：回答正文片段（无前缀）
 * - progress：阶段进度（progress: 前缀）
 * - reference：引用来源 JSON（reference: 前缀）
 * - citation：生成完成后的引用校验结果（citation: 前缀）
 */
export type RagStreamEvent =
  | { type: 'token'; chunk: string }
  | { type: 'progress'; text: string }
  | { type: 'reference'; sources: RagSourceDTO[] }
  | { type: 'citation'; metadata: RagCitationMetadata };

export interface RagSourceDTO {
  knowledgeBaseId: number | null;
  documentTitle: string;
  sourceName: string;
  category: string | null;
  sectionTitle: string | null;
  chunkIndex: number | null;
  chunkCount: number | null;
  snippet: string;
  similarity: number | null;
  cited: boolean;
}

export interface RagCitationMetadata {
  sources: RagSourceDTO[];
  confidence: number | null;
  invalidCitations: number[];
  /** pass / grounded / need_escalate */
  groundedStatus?: string | null;
}

export const ragChatApi = {
  /**
   * 创建新会话
   */
  async createSession(knowledgeBaseIds: number[], title?: string): Promise<RagChatSession> {
    return request.post<RagChatSession>('/api/rag-chat/sessions', {
      knowledgeBaseIds,
      title,
    });
  },

  /**
   * 获取会话列表
   */
  async listSessions(): Promise<RagChatSessionListItem[]> {
    return request.get<RagChatSessionListItem[]>('/api/rag-chat/sessions');
  },

  /**
   * 获取会话详情
   */
  async getSessionDetail(sessionId: number): Promise<RagChatSessionDetail> {
    return request.get<RagChatSessionDetail>(`/api/rag-chat/sessions/${sessionId}`);
  },

  /**
   * 更新会话标题
   */
  async updateSessionTitle(sessionId: number, title: string): Promise<void> {
    return request.put(`/api/rag-chat/sessions/${sessionId}/title`, { title });
  },

  /**
   * 更新会话知识库
   */
  async updateKnowledgeBases(sessionId: number, knowledgeBaseIds: number[]): Promise<void> {
    return request.put(`/api/rag-chat/sessions/${sessionId}/knowledge-bases`, {
      knowledgeBaseIds,
    });
  },

  /**
   * 切换会话置顶状态
   */
  async togglePin(sessionId: number): Promise<void> {
    return request.put(`/api/rag-chat/sessions/${sessionId}/pin`);
  },

  /**
   * 删除会话
   */
  async deleteSession(sessionId: number): Promise<void> {
    return request.delete(`/api/rag-chat/sessions/${sessionId}`);
  },

  /**
   * 发送消息（流式SSE），解析 progress:/reference:/citation:/intent: 前缀事件并分流回调。
   *
   * @param onToken 回答 token 片段（无前缀）
   * @param onProgress 阶段进度文案（progress: 前缀）
   * @param onReference 引用来源（reference: 前缀，已 JSON.parse）
   * @param onCitation 生成完成后的引用校验结果（citation: 前缀，已 JSON.parse）
   * @param onRewritten 改写后问题（rewritten: 前缀）
   * @param onIntent 意图识别结果（intent: 前缀，三路分数）
   */
  async sendMessageStream(
    sessionId: number,
    question: string,
    onToken: (chunk: string) => void,
    onComplete: () => void,
    onError: (error: Error) => void,
    onProgress?: (text: string) => void,
    onReference?: (sources: RagSourceDTO[]) => void,
    onCitation?: (metadata: RagCitationMetadata) => void,
    onCard?: (text: string) => void,
    onCardChoice?: (choices: RagCardChoice[]) => void,
    onRewritten?: (text: string) => void,
    onIntent?: (intent: IntentStreamResult) => void,
  ): Promise<void> {
    return fetchTextStream({
      url: `${API_BASE_URL}/api/rag-chat/sessions/${sessionId}/messages/stream`,
      init: {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          ...getAuthHeaders(),
        },
        body: JSON.stringify({ question }),
      },
      onMessage: (raw: string) => {
        // 前缀事件是元数据，不进回答正文
        if (raw.startsWith('progress:')) {
          onProgress?.(raw.substring('progress:'.length));
          return;
        }
        if (raw.startsWith('reference:')) {
          const payload = raw.substring('reference:'.length);
          try {
            const sources = JSON.parse(payload) as RagSourceDTO[];
            onReference?.(sources);
          } catch {
            // 忽略解析失败，不中断流
          }
          return;
        }
        if (raw.startsWith(CITATION_PREFIX)) {
          const payload = raw.substring(CITATION_PREFIX.length);
          try {
            onCitation?.(JSON.parse(payload) as RagCitationMetadata);
          } catch {
            // 忽略解析失败，不中断流
          }
          return;
        }
        if (raw.startsWith(INTENT_PREFIX)) {
          const payload = raw.substring(INTENT_PREFIX.length);
          try {
            onIntent?.(JSON.parse(payload) as IntentStreamResult);
          } catch {
            // ignore
          }
          return;
        }
        if (raw.startsWith(REWRITTEN_PREFIX)) {
          onRewritten?.(raw.substring(REWRITTEN_PREFIX.length));
          return;
        }
        if (raw.startsWith(CARD_PREFIX)) {
          onCard?.(raw.substring(CARD_PREFIX.length));
          return;
        }
        if (raw.startsWith(CARD_CHOICE_PREFIX)) {
          const payload = raw.substring(CARD_CHOICE_PREFIX.length);
          try {
            const choices = JSON.parse(payload) as RagCardChoice[];
            onCardChoice?.(choices);
          } catch {
            // ignore
          }
          return;
        }
        onToken(raw);
      },
      onComplete,
      onError,
      processBuffer: processEventStreamBuffer,
    });
  },
};
