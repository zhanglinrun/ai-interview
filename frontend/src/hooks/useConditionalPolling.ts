import {useEffect, useRef} from 'react';

type PollingCallback = () => void | Promise<void>;

export const FAST_POLLING_INTERVAL_MS = 3_000;
export const NORMAL_POLLING_INTERVAL_MS = 5_000;

export function useConditionalPolling(
  enabled: boolean,
  callback: PollingCallback,
  intervalMs: number,
) {
  const callbackRef = useRef(callback);
  const isRunningRef = useRef(false);

  useEffect(() => {
    callbackRef.current = callback;
  }, [callback]);

  useEffect(() => {
    if (!enabled) return;

    const run = async () => {
      if (isRunningRef.current) return;

      isRunningRef.current = true;
      try {
        await callbackRef.current();
      } catch (error) {
        console.error('Polling failed:', error);
      } finally {
        isRunningRef.current = false;
      }
    };

    const timer = window.setInterval(run, intervalMs);
    return () => window.clearInterval(timer);
  }, [enabled, intervalMs]);
}
