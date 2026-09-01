const TRACE_PATTERN = /^[A-Za-z0-9._-]{1,64}$/;

let lastTraceId: string | null = null;

export function isValidTraceId(value: unknown): value is string {
  return typeof value === 'string' && TRACE_PATTERN.test(value);
}

/**
 * Generate a client-side identifier when the browser provides UUID support.
 * Older browsers and non-secure contexts expose crypto without randomUUID.
 */
export function createClientId(): string {
  const cryptoApi = typeof globalThis !== 'undefined' ? globalThis.crypto : undefined;
  return cryptoApi && typeof cryptoApi.randomUUID === 'function'
    ? cryptoApi.randomUUID()
    : `${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

export function createTraceId(): string {
  return createClientId().slice(0, 64);
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
