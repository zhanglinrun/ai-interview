const FILE_SIZE_UNITS = ['B', 'KB', 'MB', 'GB', 'TB'] as const;

export function formatFileSize(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) {
    return '0 B';
  }

  const unitIndex = Math.min(
    Math.floor(Math.log(bytes) / Math.log(1024)),
    FILE_SIZE_UNITS.length - 1
  );
  const value = bytes / Math.pow(1024, unitIndex);

  return `${Number(value.toFixed(1))} ${FILE_SIZE_UNITS[unitIndex]}`;
}

export function formatDurationText(seconds?: number): string {
  if (seconds == null || !Number.isFinite(seconds) || seconds <= 0) return '-';

  const hours = Math.floor(seconds / 3600);
  const mins = Math.floor((seconds % 3600) / 60);
  const secs = Math.floor(seconds % 60);
  if (hours > 0) {
    return mins > 0 ? `${hours}小时${mins}分` : `${hours}小时`;
  }
  if (mins > 0) {
    return secs > 0 ? `${mins}分${secs}秒` : `${mins}分`;
  }
  return `${secs}秒`;
}

export function formatClockTime(seconds: number): string {
  const safeSeconds = Number.isFinite(seconds) && seconds > 0 ? Math.floor(seconds) : 0;
  const hours = Math.floor(safeSeconds / 3600);
  const mins = Math.floor((safeSeconds % 3600) / 60);
  const secs = safeSeconds % 60;
  const clock = `${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`;
  return hours > 0 ? `${hours.toString().padStart(2, '0')}:${clock}` : clock;
}

export function formatShortId(id: string, length = 8): string {
  if (!id) return '';
  return id.slice(-length);
}
