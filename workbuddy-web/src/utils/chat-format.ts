/** 聊天与轨迹共用的展示格式化。 */

/** 单行摘要：折叠空白并截断 */
export function preview(text: string, limit = 120): string {
  const flat = text.replace(/\s+/g, ' ').trim();
  return flat.length > limit ? `${flat.slice(0, limit)}…` : flat;
}

/** 参数格式化：JSON 可解析则缩进，否则原样 */
export function prettyJson(text: string): string {
  try {
    return JSON.stringify(JSON.parse(text), null, 2);
  } catch {
    return text;
  }
}

/** 时间显示：HH:mm:ss */
export function formatClock(iso: string): string {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleTimeString('zh-CN', { hour12: false });
}

/** 耗时：ms / s / m s */
export function durationLabel(startMs: number, endMs: number | null): string {
  if (endMs === null || endMs < startMs) return '';
  const ms = endMs - startMs;
  if (ms < 1000) return `${String(Math.max(1, Math.round(ms)))}ms`;
  const seconds = Math.round(ms / 1000);
  if (seconds < 60) return `${String(seconds)}s`;
  return `${String(Math.floor(seconds / 60))}m${String(seconds % 60)}s`;
}
