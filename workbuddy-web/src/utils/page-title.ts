/**
 * 浏览器页签标题：`页面名 · 知识库平台`。
 *
 * <p>**唯一写 `document.title` 的地方**：路由切换后由全局后置钩子写入页面名，
 * 详情抽屉打开时用实体名与环节名覆盖，关闭后再落回当前路由的页面名。
 *
 * <p>**平台名只在路由级标题里出现**：标签的可见宽度约 20 个汉字，带上平台名会把
 * 「文件名 · 环节名」这类靠前的关键信息挤出可见区，故实体标题不带平台名后缀。
 *
 * <p>**平台名取 `index.html` 的 `<title>`**：那份标题是首屏（脚本执行前）的兜底，
 * 这里把它拆成「兜底页面名」与「平台名」两段 —— 平台名只在这一处读，不另抄一份常量。
 */

/** 标题分段符：页面名与平台名之间 */
const TITLE_SEPARATOR = ' · ';

/** 首屏兜底标题（`index.html` 的 `<title>`），拆出的两段由本模块使用 */
const [initialTitle, appName] = splitTitle(document.title);

/** 当前路由的页面标题（抽屉覆盖标题后，关闭时落回它） */
let routeTitle = initialTitle;

/**
 * 把标题拆成「页面名 + 平台名」两段。
 *
 * @param title 完整标题；不含分段符时平台名为空串
 * @returns `[页面名, 平台名]`
 */
function splitTitle(title: string): [string, string] {
  const parts = title.split(TITLE_SEPARATOR);
  return [parts[0], parts.at(-1) ?? ''];
}

/** 合成完整标题：页面名在前、平台名在后；页面名缺失时只给平台名 */
function composeTitle(pageTitle: string): string {
  return pageTitle === '' ? appName : `${pageTitle}${TITLE_SEPARATOR}${appName}`;
}

/** 写标题；同一标题不重复赋值 */
function applyTitle(title: string): void {
  if (document.title !== title) {
    document.title = title;
  }
}

/**
 * 路由切换后写页面标题（页面名 + 平台名）。
 *
 * @param title 目标路由的 `meta.title`
 */
export function setRouteTitle(title: string): void {
  routeTitle = title;
  applyTitle(composeTitle(title));
}

/**
 * 用实体名与环节名覆盖标题（详情抽屉打开时用）；该标题不带平台名后缀。
 *
 * @param entityName 实体名（文件名）
 * @param stageName 环节名（如「解析环节」）；为空时只写实体名
 */
export function pushEntityTitle(entityName: string, stageName: string): void {
  applyTitle(stageName === '' ? entityName : `${entityName}${TITLE_SEPARATOR}${stageName}`);
}

/** 落回当前路由的页面标题（详情抽屉关闭时用） */
export function restoreTitle(): void {
  applyTitle(composeTitle(routeTitle));
}
