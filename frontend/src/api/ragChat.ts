import { request } from './request';
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

  return contentParts.join('')
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
   * 发送消息（流式SSE）
   */
  async sendMessageStream(
    sessionId: number,
    question: string,
    onMessage: (chunk: string) => void,
    onComplete: () => void,
    onError: (error: Error) => void
  ): Promise<void> {
    return fetchTextStream({
      url: `${API_BASE_URL}/api/rag-chat/sessions/${sessionId}/messages/stream`,
      init: {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ question }),
      },
      onMessage,
      onComplete,
      onError,
      processBuffer: processEventStreamBuffer,
    });
  },
};
