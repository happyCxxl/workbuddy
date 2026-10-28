/// <reference types="vite/client" />

/*
 * Element Plus 的全局组件类型。
 *
 * 组件在 main.ts 里由 `app.use(ElementPlus, { locale: zhCn })` 全量注册，但
 * **类型不会跟着注册**：不引这一行，模板里的 `<el-xxx>` 在 IDE 与 vue-tsc 眼里都是未知标签
 * （IDE 报 "Unknown html tag"，vue-tsc 因 strictTemplates 默认关闭而静默放过，
 * 于是 EP 的 prop / 事件写错也查不出来）。
 */
/// <reference types="element-plus/global" />

/*
 * 全局**指令**类型：Element Plus 的 global.d.ts 里只声明了 GlobalComponents 与
 * ComponentCustomProperties，**没有 GlobalDirectives** —— `app.use(ElementPlus)`
 * 注册的 `v-loading`（知识库列表页、用户管理页在用）在类型层面不存在，
 * IDE 会报 "Unrecognized Vue directive"。
 *
 * 这里补上同一套声明：Vue 的 `GlobalDirectives` 与 `GlobalComponents` 都是留给
 * 库作者做模块增强的空接口（EP 自己生成的 .d.ts 也引用它）。
 * 其它 EP 全局指令（如 `v-infinite-scroll`）在此加一行即可。
 *
 * 注意文件末尾的 `export {}`：**模块增强只有在模块里才生效**，
 * 少了它就只是声明了一个同名局部接口，不会并入 vue 的类型。
 */
declare module 'vue' {
  export interface GlobalDirectives {
    /** 加载遮罩：`v-loading="loading"`（对应 element-plus 的 `ElLoadingDirective`） */
    vLoading: (typeof import('element-plus'))['ElLoadingDirective'];
  }
}

/*
 * 路由元信息的类型：vue-router 的 `RouteMeta` 是留给使用方做模块增强的空接口，
 * 路由表里新增的字段要在这里登记，否则 `meta` 上取不到它（`meta.xxx` 报类型错误）。
 */
declare module 'vue-router' {
  export interface RouteMeta {
    /** 浏览器页签的页面名（与平台名合成完整标题，见 `utils/page-title.ts`） */
    title: string;
    /** 免登录页（未登录可访问） */
    public?: boolean;
    /** 仅管理员可进入 */
    adminOnly?: boolean;
  }
}

export {};
