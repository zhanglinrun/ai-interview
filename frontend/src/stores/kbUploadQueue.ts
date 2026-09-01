import { ApiError } from '../api/request';
import {
  knowledgeBaseApi,
  type BatchUploadResponse,
  type DocumentAccessScope,
} from '../api/knowledgebase';
import { createClientId } from './traceStore';

const RATE_LIMIT_CODE = 8001;
const MAX_UPLOAD_RETRIES = 3;

/**
 * 批量接口本身会逐个接收并异步处理文件，因此应尽量减少 HTTP 请求数。
 * 每批限制总大小，避免一批大 PDF 超过 nginx/Spring 的 multipart 上限；
 * 单路串行也能避免跨境链路和后端内存被并发 multipart 请求打满。
 */
export const KB_UPLOAD_BATCH_SIZE = 8;
export const KB_UPLOAD_MAX_BYTES = 240 * 1024 * 1024;
export const KB_UPLOAD_CONCURRENCY = 1;

export type KbUploadQueueStatus = 'pending' | 'uploading' | 'accepted' | 'failed';

export interface KbUploadQueueItem {
  id: string;
  fileName: string;
  status: KbUploadQueueStatus;
  error?: string;
}

interface EnqueuedFile {
  id: string;
  file: File;
  category?: string;
  accessibleBy: DocumentAccessScope;
}

type Listener = () => void;

const listeners = new Set<Listener>();
const items: KbUploadQueueItem[] = [];
const files: EnqueuedFile[] = [];
let pumping = false;

function emit() {
  listeners.forEach((listener) => listener());
}

function nextId(): string {
  return createClientId();
}

export function subscribeKbUploadQueue(listener: Listener): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function getKbUploadQueueItems(): KbUploadQueueItem[] {
  return items.slice();
}

export function getKbUploadQueueSummary() {
  const pending = items.filter((item) => item.status === 'pending' || item.status === 'uploading').length;
  const accepted = items.filter((item) => item.status === 'accepted').length;
  const failedItems = items.filter((item) => item.status === 'failed');
  return {
    total: items.length,
    pending,
    accepted,
    failed: failedItems.length,
    failedItems,
    active: pending > 0,
  };
}

/** 上传结束后关掉结果条；进行中的队列不能清，否则未发出的文件会丢。 */
export function dismissKbUploadQueueSummary(): void {
  if (items.some((item) => item.status === 'pending' || item.status === 'uploading')) {
    return;
  }
  items.length = 0;
  files.length = 0;
  emit();
}

/**
 * 把文件放进页面级队列，按批调用接收接口。
 * 队列活在模块里，离开上传页不会丢掉还没发出的文件。
 */
export function enqueueKbBatchUpload(
  selected: File[],
  options: { category?: string; accessibleBy?: DocumentAccessScope } = {},
): void {
  const accessibleBy = options.accessibleBy ?? 'PRIVATE';
  for (const file of selected) {
    const id = nextId();
    files.push({
      id,
      file,
      category: options.category,
      accessibleBy,
    });
    items.push({ id, fileName: file.name, status: 'pending' });
  }
  emit();
  void pump();
}

async function pump() {
  if (pumping) {
    return;
  }
  pumping = true;
  try {
    const workers = Array.from({ length: KB_UPLOAD_CONCURRENCY }, () => runUploadWorker());
    await Promise.all(workers);
  } finally {
    pumping = false;
    if (files.length > 0) {
      void pump();
    }
  }
}

async function runUploadWorker() {
  while (true) {
    const chunk = takeNextBatch();
    if (chunk.length === 0) {
      return;
    }
    for (const next of chunk) {
      const item = items.find((entry) => entry.id === next.id);
      if (item) {
        item.status = 'uploading';
      }
    }
    emit();
    try {
      const result = await uploadChunkWithRetry(chunk);
      applyBatchResult(chunk, result);
    } catch (error) {
      for (const next of chunk) {
        const item = items.find((entry) => entry.id === next.id);
        if (item) {
          item.status = 'failed';
          item.error = error instanceof Error ? error.message : '上传失败';
        }
      }
    }
    emit();
  }
}

function takeNextBatch(): EnqueuedFile[] {
  const first = files.shift();
  if (!first) {
    return [];
  }

  const chunk = [first];
  let totalBytes = first.file.size;
  while (chunk.length < KB_UPLOAD_BATCH_SIZE && files.length > 0) {
    const candidate = files[0];
    // 不把不同上传选项混进同一批，避免分类/可见范围串到别的任务。
    if (candidate.category !== first.category || candidate.accessibleBy !== first.accessibleBy) {
      break;
    }
    if (chunk.length > 0 && totalBytes + candidate.file.size > KB_UPLOAD_MAX_BYTES) {
      break;
    }
    files.shift();
    chunk.push(candidate);
    totalBytes += candidate.file.size;
  }
  return chunk;
}

async function uploadChunkWithRetry(chunk: EnqueuedFile[]): Promise<BatchUploadResponse> {
  let delayMs = 1000;
  let lastError: unknown;
  for (let attempt = 0; attempt <= MAX_UPLOAD_RETRIES; attempt += 1) {
    try {
      return await knowledgeBaseApi.uploadKnowledgeBaseBatch(
        chunk.map((entry) => entry.file),
        chunk[0]?.category,
        chunk[0]?.accessibleBy ?? 'PRIVATE',
      );
    } catch (error) {
      lastError = error;
      if (!isRetryableUploadError(error) || attempt === MAX_UPLOAD_RETRIES) {
        throw error;
      }
      await sleep(delayMs);
      delayMs = Math.min(delayMs * 2, 8000);
    }
  }
  throw lastError instanceof Error ? lastError : new Error('上传失败');
}

function applyBatchResult(chunk: EnqueuedFile[], result: BatchUploadResponse) {
  const pendingByName = new Map<string, Array<typeof result.items[number]>>();
  for (const remote of result.items ?? []) {
    const filename = remote.filename || '';
    const list = pendingByName.get(filename) ?? [];
    list.push(remote);
    pendingByName.set(filename, list);
  }

  for (const next of chunk) {
    const item = items.find((entry) => entry.id === next.id);
    if (!item) {
      continue;
    }
    const remote = (pendingByName.get(next.file.name) ?? []).shift();
    if (remote?.status === 'success') {
      item.status = 'accepted';
      item.error = undefined;
      continue;
    }
    if (remote?.status === 'failed') {
      item.status = 'failed';
      item.error = remote.error || '上传失败';
      continue;
    }
    if ((result.success ?? 0) > 0 && (result.failed ?? 0) === 0) {
      item.status = 'accepted';
      item.error = undefined;
      continue;
    }
    item.status = 'failed';
    item.error = '上传失败';
  }
}

function isRateLimited(error: unknown): boolean {
  return error instanceof ApiError
    ? error.code === RATE_LIMIT_CODE
    : error instanceof Error && error.message.includes('请求过于频繁');
}

function isRetryableUploadError(error: unknown): boolean {
  if (isRateLimited(error)) {
    return true;
  }
  const message = error instanceof Error ? error.message : '';
  return message.includes('网络超时')
    || message.includes('连接中断')
    || message.includes('网络连接失败')
    || message.includes('服务暂时不可用')
    || message.includes('上传请求被拒绝');
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => {
    window.setTimeout(resolve, ms);
  });
}
