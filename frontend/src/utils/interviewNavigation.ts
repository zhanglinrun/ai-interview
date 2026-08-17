export function getInterviewViewPath(sessionId: string): string {
  return `/interviews/${encodeURIComponent(sessionId)}`;
}
