/**
 * 界面折叠状态的本地持久化（侧边菜单、页面内的可折叠面板）。
 *
 * <p>**定位**：视图偏好缓存，不是业务数据。丢了只影响"刷新后是否还记得你收起过它"，
 * 任何异常一律吞掉，读不到就按默认（展开）处理。
 *
 * <p>**存储键**：`ui:{名称}`，与节点坐标（`v1:` 前缀）分开命名空间。
 *
 * <p>**只存"是否折叠"这种布尔**：固定存 `'1'` / `'0'`，不用 JSON 或对象
 * （折叠状态只有两态，用 JSON 存还要处理解析失败）；
 * 读不到或不等于 `'1'` 都按"展开"，语义单一。
 */

/** 存储键前缀 */
const STORAGE_PREFIX = 'ui:';

function storageKey(name: string): string {
  return `${STORAGE_PREFIX}${name}`;
}

/**
 * 读折叠状态。
 *
 * @param name 状态名（如 `layout-side` / `stage-files`）
 * @returns 是否已折叠；读不到或值异常时返回 false（默认展开）
 */
export function readCollapsed(name: string): boolean {
  try {
    return localStorage.getItem(storageKey(name)) === '1';
  } catch {
    // 隐私模式等场景下 localStorage 可能不可用：按默认展开，不打断页面
    return false;
  }
}

/**
 * 写折叠状态。
 *
 * @param name 状态名
 * @param collapsed 是否折叠
 */
export function writeCollapsed(name: string, collapsed: boolean): void {
  try {
    localStorage.setItem(storageKey(name), collapsed ? '1' : '0');
  } catch {
    // 配额满或不可用：这次没记住，但页面照常工作
  }
}
