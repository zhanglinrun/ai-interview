import type {VectorStatus} from '../api/knowledgebase';

export function isVectorStatusProcessing(status?: VectorStatus | null): boolean {
  return status === 'PENDING' || status === 'PROCESSING';
}

export function isVectorStatusFailed(status?: VectorStatus | null): boolean {
  return status === 'FAILED';
}
