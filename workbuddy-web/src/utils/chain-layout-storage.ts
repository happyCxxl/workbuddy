/**
 * 执行链节点坐标的本地持久化。
 *
 * <p>**定位**：这是一份「视图偏好缓存」，不是业务数据。丢了只影响「刷新后是否还记得
 * 你摆过的布局」，任何异常一律吞掉，页面照常工作。
 *
 * <p>**存储键**：`v1:{fileResultId}::{taskId}` 排成一条扁平记录，
 * 键带 `v1:` 前缀，可按文件前缀批量清理。
 *
 * <p>**不设容量上限**：一条坐标约 48 字节，一个 100 节点的文件约 4.8KB，
 * 而 localStorage 有约 5MB——够存约一千个「百节点文件」，正常使用下撞不到配额。
 *
 * <p>**配额满时**：`setItem` 会抛异常，这里吞掉——即「这次的布局没被记住」，
 * 页面不重置、不报错、功能不受影响。
 */

/** 存储键前缀（带版本号，换格式时按前缀整体废弃） */
const STORAGE_PREFIX = 'v1:';

/** 坐标的持久化形态 */
interface StoredPosition {
  x: number;
  y: number;
}

/** 分离文件维度与节点维度 */
const SEPARATOR = '::';

function storageKey(fileKey: string, taskId: string): string {
  return `${STORAGE_PREFIX}${fileKey}${SEPARATOR}${taskId}`;
}

/** 本模块写入的键，用于区分同一 localStorage 里别处的数据 */
function isOwnKey(key: string): boolean {
  return key.startsWith(STORAGE_PREFIX);
}

/** 读出某个文件的全部节点坐标；损坏或不可用时返回空表 */
export function readPositions(fileKey: string): Map<string, StoredPosition> {
  const result = new Map<string, StoredPosition>();
  const prefix = `${STORAGE_PREFIX}${fileKey}${SEPARATOR}`;
  try {
    for (let i = 0; i < localStorage.length; i += 1) {
      const key = localStorage.key(i);
      if (!key?.startsWith(prefix)) {
        continue;
      }
      const raw = localStorage.getItem(key);
      if (!raw) {
        continue;
      }
      const parsed: unknown = JSON.parse(raw);
      if (isPosition(parsed)) {
        result.set(key.slice(prefix.length), parsed);
      }
    }
  } catch {
    // 隐私模式禁用存储、或数据损坏：当作没有记录，走布局算法
    return new Map();
  }
  return result;
}

/** 写入单条坐标；配额满等情况静默跳过（页面不受影响） */
export function writePosition(fileKey: string, taskId: string, pos: StoredPosition): void {
  try {
    localStorage.setItem(storageKey(fileKey, taskId), JSON.stringify(pos));
  } catch {
    // 配额满或存储不可用：这次的布局没记住而已，不影响当前页面
  }
}

/** 删除单条坐标 */
export function removePosition(fileKey: string, taskId: string): void {
  try {
    localStorage.removeItem(storageKey(fileKey, taskId));
  } catch {
    // 同上：清理失败不影响功能
  }
}

/**
 * 清理某个文件里「已经不可能再匹配上」的坐标记录。
 *
 * <p>节点以任务 ID 为 key，而任务 ID 是一次性的：重新触发环节会产生全新 ID，
 * 旧记录永远不可能再被用到。识别办法：该文件当前的血缘里没有这个任务 ID，
 * 就说明它已经不存在了。
 *
 * <p>只清理**当前打开的这个文件**，不碰其它文件的记录。
 *
 * @param aliveTaskIds 该文件当前真实存在的任务 ID
 */
export function prunePositions(fileKey: string, aliveTaskIds: Set<string>): void {
  const prefix = `${STORAGE_PREFIX}${fileKey}${SEPARATOR}`;
  try {
    const stale: string[] = [];
    for (let i = 0; i < localStorage.length; i += 1) {
      const key = localStorage.key(i);
      if (!key?.startsWith(prefix)) {
        continue;
      }
      if (!aliveTaskIds.has(key.slice(prefix.length))) {
        stale.push(key);
      }
    }
    stale.forEach((key) => localStorage.removeItem(key));
  } catch {
    // 清理失败只意味着多留几条死数据，不影响功能
  }
}

/** 丢弃本模块写入的全部记录（数据格式升级或整体重置时用） */
export function clearPositions(): void {
  try {
    const keys: string[] = [];
    for (let i = 0; i < localStorage.length; i += 1) {
      const key = localStorage.key(i);
      if (key && isOwnKey(key)) {
        keys.push(key);
      }
    }
    keys.forEach((key) => localStorage.removeItem(key));
  } catch {
    // 同上：失败不影响功能
  }
}

/** 校验读到的值确实是坐标（localStorage 的内容可能被别处或旧版本污染） */
function isPosition(value: unknown): value is StoredPosition {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const candidate = value as Record<string, unknown>;
  return typeof candidate.x === 'number' && typeof candidate.y === 'number';
}
