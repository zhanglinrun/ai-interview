import type {DocStatus} from '../api/knowledgebase';

// 处理中：文档尚未完成向量化（CONVERTING 解析中 / CONVERTED 待切块 / CHUNKED 待向量化）
export function isVectorStatusProcessing(status?: DocStatus | null): boolean {
  return status === 'INIT'
    || status === 'UPLOADED'
    || status === 'CONVERTING'
    || status === 'CONVERTED'
    || status === 'CHUNKED';
}

// 可手动重试：新链路无显式 FAILED，卡在 CHUNKED（切块完成但向量化未完成）可触发 rechunk
export function isVectorStatusFailed(status?: DocStatus | null): boolean {
  return status === 'CHUNKED';
}
