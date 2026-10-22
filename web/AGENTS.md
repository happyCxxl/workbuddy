# web/AGENTS.md — 前端功能域入口（knowledge-web）

> 编辑 `web/` 下任何文件时自动加载。全局口径（注释、提交、门禁、依赖、--no-verify 禁令）以根 `AGENTS.md` 为准，此处不重复。

## 1. 定位与命令

- Vue 3 + TypeScript + Vite + Element Plus + Pinia；界面文案纯中文。
- dev server `127.0.0.1:5173`，接口经 Vite 代理转发到后端 `localhost:4388`。

| 场景       | 命令          | 要点                                                                                                |
| ---------- | ------------- | --------------------------------------------------------------------------------------------------- |
| 全量校验   | `pnpm lint`   | 八步：格式 / eslint / vue-tsc / stylelint / 文件命名 / 命名词表 / 规范合规 / 表格口径；全量只报不改 |
| 就地格式化 | `pnpm format` | 提交钩子已对暂存文件自动做，一般无需手动                                                            |
| 本地开发   | `pnpm dev`    | 依赖后端 4388 与 `docker/` 基础设施                                                                 |
| 构建       | `pnpm build`  | vue-tsc -b + vite build                                                                             |

## 2. 前端 Never（违反即不合规；完整条款以规范正典为准）

1. HTTP 调用只出现在 `src/api/`（一控制器一文件，如 `KnowledgeBaseController` → `src/api/knowledge-base.ts`）；页面/组件
   不直接写请求；接口函数返回类型显式声明，禁 `any`。
2. ID 字段一律字符串类型（后端 Long 超 2^53-1 序列化为字符串）；时间展示统一经 `src/utils/` 时间工具格式化为
   `yyyy-MM-dd HH:mm:ss`，禁散落 `new Date(...).toLocaleString()`。
3. 列表表格走全局骨架类（`page-table` / `page-table-body` / `page-table-empty`）；同表各列同一个 `min-width` 字面量、
   不写 `width`；列统一 `align="center"`（左对齐列不写 align）。
4. 新增接口前缀必须同步 `vite.config.ts` 代理——漏加时请求落到 SPA 回退，页面拿 HTML、控制台不报错。
5. 页面 `catch {}` 留空是正常写法（失败提示由 `src/api/http.ts` 拦截器统一负责），不要"修复"它；全局状态只放跨页面
   共享数据，变更一律经 actions。

## 3. 必读与交付

- 动手前必读：`.agent/standards/vue/00-规则索引.md`（59 条编号规则，分四份：索引、工程与命名、组件与样式、异步与测试；本文 Never 只是其中红线的速查，条款冲突以规范为准）。
- 交付自查：`.agent/standards/vue/00-规则索引.md` §6 的评审项（机器层已由钩子覆盖，评审层逐项核对）。
- 检查器组成与豁免口径：`.agent/standards/common/07-检查手段.md`。
