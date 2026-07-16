import {isCompletedInterviewStatus} from './interviewStatus';

export function getInterviewViewPath(
  sessionId: string,
  jobInterview?: boolean,
  status?: string,
): string {
  const encodedSessionId = encodeURIComponent(sessionId);
  if (!jobInterview) {
    return `/interviews/${encodedSessionId}`;
  }
  return isCompletedInterviewStatus(status)
    ? `/job-practice/report/${encodedSessionId}`
    : `/job-practice/session/${encodedSessionId}`;
}
