const STARTED_AT_PREFIX = 'interview-started-at:';

export function resolveInterviewStartedAt(
  sessionId: string,
  createdAt?: string | null,
): string {
  if (createdAt) {
    return createdAt;
  }
  if (typeof sessionStorage === 'undefined') {
    return new Date().toISOString();
  }
  const key = STARTED_AT_PREFIX + sessionId;
  const stored = sessionStorage.getItem(key);
  if (stored) {
    return stored;
  }
  const now = new Date().toISOString();
  sessionStorage.setItem(key, now);
  return now;
}
