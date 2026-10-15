import { createRouter, createWebHistory } from 'vue-router';

import { useAuthStore } from '@/stores/auth';
import { setRouteTitle } from '@/utils/page-title';

/** 工作台路径：未登录的落点与越权访问的回落目标都是它 */
const HOME_PATH = '/home';

// 路由表：页面按规范 §7 懒加载；path 用 kebab-case，name 与组件名保持一致；
// meta.title 是浏览器页签的页面名（与平台名合成完整标题，见 utils/page-title.ts）
const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'Login',
      component: () => import('@/views/login/LoginView.vue'),
      // 免登录页：已登录用户访问时回落到工作台
      meta: { title: '登录', public: true },
    },
    {
      path: '/',
      name: 'AppLayout',
      component: () => import('@/layouts/AppLayout.vue'),
      redirect: HOME_PATH,
      children: [
        {
          // 系统首页：登录后的默认落地页，也是全局返回的终点
          path: 'home',
          name: 'Home',
          component: () => import('@/views/home/HomeView.vue'),
          meta: { title: '首页' },
        },
        {
          // 知识库管理：列表页与它的三个下钻页（执行链 / 索引与发布 / 检索评测）共用这一项
          path: 'knowledge/manager',
          name: 'KnowledgeBase',
          component: () => import('@/views/knowledge-base/KnowledgeBaseView.vue'),
          meta: { title: '知识库' },
        },
        {
          // 执行链：左侧文件列表 + 右侧链图，四个环节的卡片与详情抽屉都在这里
          path: 'knowledge/:id/stages',
          name: 'PipelineStage',
          component: () => import('@/views/knowledge-base/PipelineStageView.vue'),
          meta: { title: '执行链' },
        },
        {
          // 索引与发布：同一知识库的策略组合版本、发布与回退
          path: 'knowledge/:id/index',
          name: 'IndexBuild',
          component: () => import('@/views/knowledge-base/IndexBuildView.vue'),
          meta: { title: '索引与发布' },
        },
        {
          // 检索评测：测试台检索 + 运行记录并排对比 + 规则选优发布
          path: 'knowledge/:id/retrieval',
          name: 'RetrievalEval',
          component: () => import('@/views/knowledge-base/RetrievalEvalView.vue'),
          meta: { title: '检索评测' },
        },
        {
          // 策略管理：四类策略（预处理/切片/向量化/检索）的版本管理
          path: 'knowledge/strategy',
          name: 'StrategyManagement',
          component: () => import('@/views/strategy/StrategyManagementView.vue'),
          meta: { title: '策略管理' },
        },
        {
          path: 'user',
          name: 'UserManagement',
          component: () => import('@/views/user/UserManagementView.vue'),
          // 仅管理员可进入；非管理员回落工作台
          meta: { title: '用户管理', adminOnly: true },
        },
        {
          // 模型清单：模型目录维护（新增 / 编辑 / 启停）；页面与接口对所有登录用户开放
          path: 'model/manager',
          name: 'ModelList',
          component: () => import('@/views/model/ModelListView.vue'),
          meta: { title: '模型清单' },
        },
        {
          // 用量与成本：调用概览与明细；与模型清单同属「模型管理」菜单
          path: 'model/usage',
          name: 'ModelUsage',
          component: () => import('@/views/model/ModelUsageView.vue'),
          meta: { title: '用量与成本' },
        },
        {
          // 系统设置：平台级开关，登录用户都能进；页面内各设置卡按权限自行决定是否渲染
          path: 'settings',
          name: 'SystemSetting',
          component: () => import('@/views/system/SystemSettingView.vue'),
          meta: { title: '系统设置' },
        },
        {
          // 个人中心：账号信息、联系方式与修改密码；入口在布局壳的用户菜单
          path: 'profile',
          name: 'Profile',
          component: () => import('@/views/profile/ProfileView.vue'),
          meta: { title: '个人中心' },
        },
      ],
    },
  ],
});

// 登录态与角色守卫：无令牌回登录页并记住来源；已登录访问登录页回首页；
// 非管理员访问管理类页面回首页
router.beforeEach((to) => {
  const authStore = useAuthStore();
  if (to.meta.public) {
    return authStore.isLoggedIn ? { path: HOME_PATH } : true;
  }
  if (!authStore.isLoggedIn) {
    return { path: '/login', query: { redirect: to.fullPath } };
  }
  if (to.meta.adminOnly && !authStore.isAdmin) {
    return { path: HOME_PATH };
  }
  return true;
});

// 标题只在此处按目标路由写一次；抽屉覆盖标题期间的切换由标题模块记下新值
router.afterEach((to) => {
  setRouteTitle(to.meta.title);
});

export default router;
