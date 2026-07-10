import { API_BASE_URL, getErrorMessage, notifyIfUserLlmNotConfigured } from './request';

export { API_BASE_URL };

type StreamEmitter = (chunk: string) => void;
type StreamBufferProcessor = (
  buffer: string,
  emit: StreamEmitter,
  isFinal: boolean
) => string;

interface FetchTextStreamOptions {
  url: string;
  init: RequestInit;
  onMessage: StreamEmitter;
  onComplete: () => void;
  onError: (error: Error) => void;
  processBuffer: StreamBufferProcessor;
}

function hasErrorMessage(value: unknown): value is { message: string } {
  if (!value || typeof value !== 'object') {
    return false;
  }
  const candidate = value as { message?: unknown };
  return typeof candidate.message === 'string' && candidate.message.length > 0;
}

function readErrorCode(value: unknown): number | undefined {
  if (!value || typeof value !== 'object') {
    return undefined;
  }
  const candidate = value as { code?: unknown };
  return typeof candidate.code === 'number' ? candidate.code : undefined;
}

async function ensureStreamResponse(response: Response): Promise<void> {
  if (response.ok) {
    return;
  }

  let message: string | null = null;
  let code: number | undefined;
  try {
    const errorData = await response.json() as unknown;
    if (hasErrorMessage(errorData)) {
      message = errorData.message;
    }
    code = readErrorCode(errorData);
  } catch {
    message = null;
  }

  // 流式 chat（RAG 问答/面试）未配置模型 Key 时同样触发全局引导
  notifyIfUserLlmNotConfigured(code, message ?? undefined);
  throw new Error(message ?? `请求失败 (${response.status})`);
}

export async function fetchTextStream(options: FetchTextStreamOptions): Promise<void> {
  const {
    url,
    init,
    onMessage,
    onComplete,
    onError,
    processBuffer,
  } = options;

  try {
    const response = await fetch(url, init);
    await ensureStreamResponse(response);

    const reader = response.body?.getReader();
    if (!reader) {
      throw new Error('无法获取响应流');
    }

    const decoder = new TextDecoder();
    let buffer = '';

    while (true) {
      const { done, value } = await reader.read();
      if (done) {
        buffer += decoder.decode();
        if (buffer) {
          processBuffer(buffer, onMessage, true);
        }
        onComplete();
        break;
      }

      buffer += decoder.decode(value, { stream: true });
      buffer = processBuffer(buffer, onMessage, false);
    }
  } catch (error) {
    onError(new Error(getErrorMessage(error)));
  }
}
