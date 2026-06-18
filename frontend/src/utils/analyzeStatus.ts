import type {AnalyzeStatus} from '../api/history';

export function isAnalyzeStatusProcessing(status?: AnalyzeStatus | null): boolean {
  return status === 'PENDING' || status === 'PROCESSING';
}

export function isAnalyzeStatusRunning(status?: AnalyzeStatus | null): boolean {
  return status === 'PROCESSING';
}

export function isAnalyzeStatusCompleted(status?: AnalyzeStatus | null): boolean {
  return status === 'COMPLETED';
}

export function isAnalyzeStatusFailed(status?: AnalyzeStatus | null): boolean {
  return status === 'FAILED';
}

export function hasCompletedAnalyzeResult(
  status: AnalyzeStatus | undefined,
  hasResult: boolean,
): boolean {
  return isAnalyzeStatusCompleted(status) || (status === undefined && hasResult);
}

export function shouldPollAnalyzeResult(
  status: AnalyzeStatus | undefined,
  hasResult: boolean,
): boolean {
  return isAnalyzeStatusProcessing(status) || (status === undefined && !hasResult);
}
