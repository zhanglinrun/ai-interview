/**
 * 日期格式化工具函数
 */

/**
 * 格式化日期为中文格式
 * @param dateStr 日期字符串
 * @param options 格式化选项
 * @returns 格式化后的日期字符串
 */
type DateFormatOptions = Pick<
  Intl.DateTimeFormatOptions,
  'year' | 'month' | 'day' | 'hour' | 'minute'
>;

function parseValidDate(dateStr: string | null | undefined): Date | null {
  if (!dateStr) return null;

  const date = new Date(dateStr);
  return Number.isFinite(date.getTime()) ? date : null;
}

export function formatDate(
  dateStr: string | null | undefined,
  options?: DateFormatOptions
): string {
  const date = parseValidDate(dateStr);
  if (!date) return '-';

  const defaultOptions: Intl.DateTimeFormatOptions = {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    ...options
  };
  
  return date.toLocaleDateString('zh-CN', defaultOptions);
}

/**
 * 格式化日期时间（包含时分）
 */
export function formatDateTime(dateStr: string | null | undefined): string {
  return formatDate(dateStr, {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  });
}

/**
 * 格式化日期（仅日期部分）
 */
export function formatDateOnly(dateStr: string | null | undefined): string {
  return formatDate(dateStr, {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit'
  });
}

/**
 * 格式化为相对时间，用于会话列表等短时间提示。
 */
export function formatTimeAgo(dateStr: string | null | undefined): string {
  const date = parseValidDate(dateStr);
  if (!date) return '-';

  const diff = Date.now() - date.getTime();
  const minutes = Math.floor(diff / 60000);
  const hours = Math.floor(diff / 3600000);
  const days = Math.floor(diff / 86400000);

  if (minutes < 1) return '刚刚';
  if (minutes < 60) return `${minutes} 分钟前`;
  if (hours < 24) return `${hours} 小时前`;
  if (days < 7) return `${days} 天前`;
  return formatDateOnly(dateStr);
}

export function getDateTime(dateStr: string | null | undefined): number {
  return parseValidDate(dateStr)?.getTime() ?? 0;
}

export function getElapsedSecondsSince(dateStr: string | null | undefined): number {
  const startTime = getDateTime(dateStr);
  if (startTime <= 0) return 0;

  return Math.max(0, Math.floor((Date.now() - startTime) / 1000));
}

export function compareDateAsc(
  left: string | null | undefined,
  right: string | null | undefined
): number {
  return getDateTime(left) - getDateTime(right);
}

export function compareDateDesc(
  left: string | null | undefined,
  right: string | null | undefined
): number {
  return getDateTime(right) - getDateTime(left);
}

