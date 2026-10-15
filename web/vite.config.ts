import { fileURLToPath, URL } from 'node:url';
import type { IncomingMessage } from 'node:http';

import vue from '@vitejs/plugin-vue';
import { defineConfig } from 'vite';

const BACKEND = 'http://localhost:4388';

/**
 * 浏览器页面导航（要 HTML）不走后端代理，交回 Vite 的 SPA 回退。
 *
 * <p>接口前缀与 SPA 路由会重叠（`/knowledge-base` 既是页面路由又是接口前缀），
 * 只靠路径无法区分，否则直接访问页面会被转发给后端、把接口的 JSON 当页面渲染。
 * 这里按 Accept 头区分：导航请求 → 返回路径跳过代理；接口请求（axios，Accept 为 json）→ 正常代理。
 *
 * @param req 代理收到的请求
 * @returns 需要跳过代理的路径；返回 undefined 表示继续代理
 */
function bypassNavigation(req: IncomingMessage): string | undefined {
  const accept = req.headers.accept ?? '';
  if (accept.includes('text/html')) {
    return req.url;
  }
  return undefined;
}

// Vite 配置：Vue 插件 + @ 别名 + 后端接口开发代理（开发期经代理转发，免 CORS）
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    host: '127.0.0.1',
    port: 5173,
    watch: {
      /**
       * 忽略「原子替换式保存」留下的临时文件。
       *
       * <p>不少编辑器/工具保存文件时不是原地写，而是先写一个临时文件再改名替换
       * （形如 `.Foo.vue.12345.<uuid>.tmpdir/Foo.vue.tmp`）。chokidar 会把这些临时路径
       * 也加进监听，而它们往往在注册监听前就被删掉，于是抛
       * `EBUSY: resource busy or locked, watch ...tmpdir/...tmp` 把整个 dev server 打挂。
       *
       * <p>匹配用「点号开头的路径段」，正常源码不会以点开头，所以不会误伤业务文件。
       */
      ignored: ['**/.*.tmpdir/**', '**/.*.tmp'],
    },
    proxy: {
      '/auth': { target: BACKEND, bypass: bypassNavigation },
      // 文件上传与下载（POST /files 返回 fileId，导入建档时用）
      '/files': { target: BACKEND, bypass: bypassNavigation },
      // 执行链与各环节详情：/file-results/{id}/lineage、{stage}、{stage}-detail、stage-content
      '/file-results': { target: BACKEND, bypass: bypassNavigation },
      // 知识库接口：/knowledge-base 只是接口前缀，对应的 SPA 路由已改为 /knowledge/*，
      // 这里保留 bypass 是为了口径统一、防未来重名
      '/knowledge-base': { target: BACKEND, bypass: bypassNavigation },
      // /strategy-versions 目前仅接口使用，加 bypass 是为了口径统一、防未来重名
      '/strategy-versions': { target: BACKEND, bypass: bypassNavigation },
      // 用户接口：只代理 /user/ 下的子路径，避免把 SPA 路由 /user 本身也转发到后端
      '^/user/': { target: BACKEND, bypass: bypassNavigation },
      // 首页接口（/home/summary、/home/recent-submits）：/home 同时是 SPA 路由，
      // 靠 bypass 按 Accept 头区分导航与接口 —— 漏配这条会让接口请求落到 SPA 回退上，
      // 表现为前端拿到一坨 HTML、页面数据全空（且控制台不报错，很难发现）
      '^/home/': { target: BACKEND, bypass: bypassNavigation },
      // 系统设置接口（/system/storage-sources 与它的预览、启用）：/settings 是 SPA 路由而
      // /system 同时是接口前缀，同样靠 bypass 区分导航与接口；漏配的后果与上面 /home 那条相同
      '^/system/': { target: BACKEND, bypass: bypassNavigation },
      // 模型管理接口（/model/models 与 /model/models/{id}/status、/model/usage/*）：/model 同时是
      // SPA 路由，这里只代理带子路径的接口，并靠 bypass 按 Accept 头区分导航与接口
      '^/model/': { target: BACKEND, bypass: bypassNavigation },
    },
  },
});
