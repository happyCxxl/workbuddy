/**
 * 解析详情抽屉左右栏比例（分隔条位置）的本地持久化。
 *
 * <p>**定位**：这是一份「视图偏好缓存」，不是业务数据。丢了只影响「下次打开是否还记得
 * 你拖过的栏宽」，任何异常一律吞掉，页面照常按默认比例渲染。
 *
 * <p>**存储键**：`v1:drawer-split::{fileResultId}::{taskId}`，键带 `v1:` 前缀，
 * 换格式时可按前缀整体废弃，也与执行链坐标那份缓存区分得开。
 *
 * <p>**分键粒度**：按「文件 + 运行」分键。不同文档的排版宽窄不同（扫描件窄、宽表格宽），
 * 一个全局值会把上一份文档的偏好带到下一份。
 *
 * <p>**存的是比例不是像素**：窗口大小与抽屉宽度都会变，像素值换个屏幕就不适用；
 * 比例在读取时再按当前容器宽度换算，并同样受最小宽度约束。
 */

/** 存储键前缀（带版本号，换格式时按前缀整体废弃） */
const STORAGE_PREFIX = 'v1:drawer-split::';

/** 左栏比例的合法区间：超出即视为无效记录（宁可走默认值，也不用离谱的值） */
const MIN_RATIO = 0.2;
const MAX_RATIO = 0.9;

/** 分离文件维度与运行维度 */
const SEPARATOR = '::';

function storageKey(fileResultId: string, taskId: string): string {
  return `${STORAGE_PREFIX}${fileResultId}${SEPARATOR}${taskId}`;
}

/**
 * 读取某次运行记住的左栏比例。
 *
 * @returns 合法比例（0.2–0.9）；没有记录或记录损坏时返回 null，由调用方走默认比例
 */
export function readSplitRatio(fileResultId: string, taskId: string): number | null {
  try {
    const raw = localStorage.getItem(storageKey(fileResultId, taskId));
    if (!raw) {
      return null;
    }
    const value = Number(raw);
    if (!Number.isFinite(value) || value < MIN_RATIO || value > MAX_RATIO) {
      return null;
    }
    return value;
  } catch {
    // 隐私模式禁用存储、或数据被污染：当作没有记录，走默认比例
    return null;
  }
}

/**
 * 记住某次运行的左栏比例；配额满等情况静默跳过（页面不受影响）。
 *
 * @param ratio 左栏占双栏的比例（调用方已按最小宽度夹过，这里只再挡一次离谱值）
 */
export function writeSplitRatio(fileResultId: string, taskId: string, ratio: number): void {
  if (!Number.isFinite(ratio) || ratio < MIN_RATIO || ratio > MAX_RATIO) {
    return;
  }
  try {
    localStorage.setItem(storageKey(fileResultId, taskId), String(ratio));
  } catch {
    // 配额满或存储不可用：这次的栏宽没记住而已，不影响当前页面
  }
}

/** 忘掉某次运行的比例（双击恢复默认时用，让"默认"与"没记录过"是同一种状态） */
export function removeSplitRatio(fileResultId: string, taskId: string): void {
  try {
    localStorage.removeItem(storageKey(fileResultId, taskId));
  } catch {
    // 同上：清理失败不影响功能
  }
}
