import { useEffect, useState } from 'react';
import { getElapsedSecondsSince } from '../utils/date';

export function useElapsedSeconds(startedAt: string | null | undefined, running = true): number {
  const [seconds, setSeconds] = useState(() => getElapsedSecondsSince(startedAt));

  useEffect(() => {
    setSeconds(getElapsedSecondsSince(startedAt));
    if (!running || !startedAt) {
      return;
    }
    const timer = window.setInterval(() => {
      setSeconds(getElapsedSecondsSince(startedAt));
    }, 1000);
    return () => window.clearInterval(timer);
  }, [running, startedAt]);

  return seconds;
}
