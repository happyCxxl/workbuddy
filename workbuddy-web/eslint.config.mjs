import js from '@eslint/js';
import pluginVue from 'eslint-plugin-vue';
import tseslint from 'typescript-eslint';

export default [
  {
    ignores: ['node_modules/**', 'dist/**', 'public/**'],
  },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  ...pluginVue.configs['flat/strongly-recommended'],
  {
    files: ['**/*.vue'],
    languageOptions: {
      parserOptions: {
        parser: tseslint.parser,
      },
      // .vue 的 script 块同样运行在浏览器：typescript-eslint 只对 .ts 关闭 no-undef，
      // 这里显式声明全局，避免 window / requestAnimationFrame 一类被误报
      globals: {
        window: 'readonly',
        document: 'readonly',
        navigator: 'readonly',
        performance: 'readonly',
        localStorage: 'readonly',
        sessionStorage: 'readonly',
        setTimeout: 'readonly',
        clearTimeout: 'readonly',
        setInterval: 'readonly',
        clearInterval: 'readonly',
        requestAnimationFrame: 'readonly',
        cancelAnimationFrame: 'readonly',
        ResizeObserver: 'readonly',
        matchMedia: 'readonly',
      },
    },
  },
  {
    files: ['**/*.{js,mjs,cjs}'],
    languageOptions: {
      ecmaVersion: 2023,
      sourceType: 'module',
      globals: {
        console: 'readonly',
        window: 'readonly',
        document: 'readonly',
        navigator: 'readonly',
        localStorage: 'readonly',
        sessionStorage: 'readonly',
        process: 'readonly',
        setTimeout: 'readonly',
        clearTimeout: 'readonly',
        setInterval: 'readonly',
        clearInterval: 'readonly',
      },
    },
    rules: {
      // 语言底线（VUE-MACHINE-001 / VUE-STYLE-001）：const 优先 / 禁 var / 大括号必加 / 严格相等 / 字面量
      'no-var': 'error',
      'prefer-const': 'error',
      curly: ['error', 'all'],
      // 与 null 字面量比较时放行 ==/!=：同时覆盖 null 与 undefined（对齐 Google TS 风格指南的例外）
      eqeqeq: ['error', 'always', { null: 'ignore' }],
      'no-new-object': 'error',
      'no-array-constructor': 'error',
      quotes: ['error', 'single', { avoidEscape: true }],
      semi: ['error', 'always'],
      // 调试日志不得提交（见规范「命名」【应该】：调试输出不得提交，先告警）
      'no-console': 'warn',
    },
  },
  {
    files: ['src/**/*.{ts,vue}'],
    rules: {
      // VUE-API-002：类型化接口层，禁止 any
      '@typescript-eslint/no-explicit-any': 'error',
      '@typescript-eslint/no-unused-vars': 'error',
      // 接口层收口（VUE-API-001）：HTTP 库只允许出现在 src/api 内
      'no-restricted-imports': [
        'error',
        {
          paths: [{ name: 'axios', message: 'HTTP 调用只允许写在 src/api/ 目录（VUE-API-001）' }],
        },
      ],
      // VUE-COMP-007：模板组件名 PascalCase + 自闭合
      'vue/component-name-in-template-casing': ['error', 'PascalCase'],
      'vue/html-self-closing': [
        'error',
        { html: { void: 'always', normal: 'never', component: 'always' } },
      ],
      // 格式细节由 Prettier 统一负责，关闭与其冲突的布局规则
      // （Prettier 会把较长的开始标签的 `>` 折到下一行，这两条布局规则会与之互相打架）
      'vue/max-attributes-per-line': 'off',
      'vue/singleline-html-element-content-newline': 'off',
      'vue/html-closing-bracket-newline': 'off',
      'vue/html-indent': 'off',
      // 元素 attribute 顺序（见规范「组件」【应该】：标识→绑定→事件，告警级）
      'vue/attributes-order': 'warn',
      /*
       * 指令名拼错（v-loadin 之类）在别处都查不出来：`v-loading` 由
       * `app.use(ElementPlus)` 在运行时全局注册，类型层面靠 src/env.d.ts 的
       * `GlobalDirectives` 声明，而 vue-tsc 不解析指令（strictTemplates 默认关闭）——
       * 所以这条规则是**唯一能在门禁里拦住它的地方**。
       *
       * ignore 里登记的是「运行时全局注册、源码里查不到定义」的指令：
       * - loading：Element Plus 的 ElLoadingDirective（首页表格、知识库网格、用户表格在用）。
       * 以后引入别的全局指令（如 v-infinite-scroll），在这里补一项，而不是把规则关掉。
       */
      'vue/no-undef-directives': ['error', { ignore: ['loading'] }],
    },
  },
  {
    files: ['src/**/*.{ts,vue}'],
    languageOptions: {
      parserOptions: {
        project: './tsconfig.json',
        extraFileExtensions: ['.vue'],
      },
    },
    rules: {
      // 类型感知规则：补齐 IDEA 数据流检查一类（恒真条件 / 冗余断言 / 可简化表达式）
      '@typescript-eslint/no-unnecessary-condition': 'error',
      '@typescript-eslint/no-unnecessary-type-assertion': 'error',
      '@typescript-eslint/no-unnecessary-boolean-literal-compare': 'error',
      '@typescript-eslint/no-unnecessary-template-expression': 'error',
      '@typescript-eslint/prefer-optional-chain': 'error',
      // VUE-NAME-001 / VUE-NAME-005：命名口径（变量/函数 camelCase、常量 UPPER_CASE、类型 PascalCase）与嵌套 ≤3 层
      '@typescript-eslint/naming-convention': [
        'error',
        { selector: 'variable', format: ['camelCase', 'UPPER_CASE'] },
        { selector: 'function', format: ['camelCase'] },
        { selector: 'parameter', format: ['camelCase'], leadingUnderscore: 'allow' },
        { selector: 'typeLike', format: ['PascalCase'] },
      ],
      'max-depth': ['error', 3],
    },
  },
  {
    files: ['src/api/**/*.{ts,vue}'],
    rules: {
      'no-restricted-imports': 'off',
    },
  },
];
