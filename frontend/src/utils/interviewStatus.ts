import type {EvaluateStatus} from '../api/history';

export type InterviewStatus = string | null | undefined;
export type EvaluationStatus = EvaluateStatus | null | undefined;

export function isCompletedInterviewStatus(status: InterviewStatus): boolean {
  return status === 'COMPLETED' || status === 'EVALUATED';
}

export function isLiveInterviewStatus(status: InterviewStatus): boolean {
  return status === 'IN_PROGRESS' || status === 'PAUSED';
}

export function isActiveInterviewStatus(status: InterviewStatus): boolean {
  return status === 'IN_PROGRESS';
}

export function isEvaluationCompleted(
  evaluateStatus: EvaluationStatus,
  interviewStatus?: InterviewStatus
): boolean {
  return evaluateStatus === 'COMPLETED' || interviewStatus === 'EVALUATED';
}

export function isEvaluationProcessing(evaluateStatus: EvaluationStatus): boolean {
  return evaluateStatus === 'PENDING' || evaluateStatus === 'PROCESSING';
}

export function isEvaluationFailed(evaluateStatus: EvaluationStatus): boolean {
  return evaluateStatus === 'FAILED';
}

export function isDegradedEvaluationFeedback(text?: string | null): boolean {
  if (!text) return false;
  return /按 0 分处理|按 0 分兜底|未成功生成评估结果|批次评估失败|均按0分兜底/.test(text);
}

export function hasReliableEvaluationScore(options: {
  evaluateStatus?: EvaluationStatus;
  status?: InterviewStatus;
  overallScore?: number | null;
  evaluationDegraded?: boolean;
  overallFeedback?: string | null;
}): boolean {
  if (isEvaluationFailed(options.evaluateStatus)) return false;
  if (options.overallScore == null) return false;
  if (options.evaluationDegraded) return false;
  if (isDegradedEvaluationFeedback(options.overallFeedback)) return false;
  return isEvaluationCompleted(options.evaluateStatus, options.status);
}

export function getInterviewStatusText(
  interviewStatus: InterviewStatus,
  evaluateStatus: EvaluationStatus
): string {
  if (isEvaluationFailed(evaluateStatus)) return '评估失败';
  if (isEvaluationProcessing(evaluateStatus)) {
    return evaluateStatus === 'PROCESSING' ? '评估中' : '等待评估';
  }
  if (isEvaluationCompleted(evaluateStatus, interviewStatus)) return '已完成';
  if (isActiveInterviewStatus(interviewStatus)) return '进行中';
  if (interviewStatus === 'PAUSED') return '已暂停';
  if (interviewStatus === 'COMPLETING') return '报告生成中';
  if (interviewStatus === 'ABORTED') return '已中止';
  if (interviewStatus === 'FAILED') return '异常结束';
  if (isCompletedInterviewStatus(interviewStatus)) return '已完成';
  if (interviewStatus === 'READY') return '待开始';
  if (interviewStatus === 'CREATED') return '已创建';
  return interviewStatus?.trim() ? interviewStatus : '已创建';
}
