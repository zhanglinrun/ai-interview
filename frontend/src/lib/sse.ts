/**
 * v1 SSE envelope 的协议级解析，不绑定具体业务 feature。
 * RAG、Agent 和面试流都可以复用这套校验，避免各页面重复猜测 JSON。
 */
export interface StructuredSseEvent {
  traceId: string;
  ragRunId?: string;
  sequence: number;
  stage: string;
  event: string;
  timestamp: string;
  payload: unknown;
}

export function isStructuredSseEvent(value: string): boolean {
  if (!value.trim().startsWith('{')) {
    return false;
  }
  try {
    const parsed = JSON.parse(value) as Partial<StructuredSseEvent>;
    return typeof parsed === 'object'
      && parsed !== null
      && typeof parsed.traceId === 'string'
      && typeof parsed.sequence === 'number'
      && typeof parsed.stage === 'string'
      && typeof parsed.event === 'string'
      && typeof parsed.timestamp === 'string'
      && Object.prototype.hasOwnProperty.call(parsed, 'payload');
  } catch {
    return false;
  }
}

export function parseStructuredSseEvent(value: string): StructuredSseEvent | null {
  if (!isStructuredSseEvent(value)) {
    return null;
  }
  try {
    return JSON.parse(value) as StructuredSseEvent;
  } catch {
    return null;
  }
}
