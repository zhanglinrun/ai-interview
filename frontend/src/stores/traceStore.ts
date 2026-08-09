const TRACE_PATTERN = /^[A-Za-z0-9._-]{1,64}$/;

let lastTraceId: string | null = null;

export function isValidTraceId(value: unknown): value is string {
  return typeof value === 'string' && TRACE_PATTERN.test(value);
}

export function createTraceId(): string {
  const uuid = typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  return uuid.slice(0, 64);
}

export function rememberTraceId(value: unknown): string | null {
  if (!isValidTraceId(value)) {
    return null;
  }
  lastTraceId = value;
  return value;
}

export function getLastTraceId(): string | null {
  return lastTraceId;
}
