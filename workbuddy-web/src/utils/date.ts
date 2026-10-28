// 时间格式化工具：后端 ISO 时间统一转展示格式
function pad(part: number): string {
  return String(part).padStart(2, '0');
}

function toDate(value: string): Date | null {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return null;
  }
  return date;
}

// ISO 时间转 yyyy-MM-dd HH:mm:ss
export function formatDateTime(value: string): string {
  const date = toDate(value);
  if (!date) {
    return value;
  }
  return `${formatDate(value)} ${formatTime(value)}:${pad(date.getSeconds())}`;
}

// ISO 时间转 yyyy-MM-dd
export function formatDate(value: string): string {
  const date = toDate(value);
  if (!date) {
    return value;
  }
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

// ISO 时间转 HH:mm
export function formatTime(value: string): string {
  const date = toDate(value);
  if (!date) {
    return value;
  }
  return `${pad(date.getHours())}:${pad(date.getMinutes())}`;
}
