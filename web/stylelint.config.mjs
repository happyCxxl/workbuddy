/** @type {import('stylelint').Config} */
// noinspection JSUnusedGlobalSymbols -- 默认导出由 stylelint 按约定消费，非 JS 代码引用
export default {
  extends: ['stylelint-config-standard'],
  ignoreFiles: ['node_modules/**', 'dist/**', 'public/**'],
  rules: {
    // VUE-MACHINE-001：语义类名 kebab-case 由工具强制；Element Plus 内部 BEM 类作为第三方命名放行（理由见规范 MACHINE-001 边界）
    //   `__` 双下划线 = BEM 元素（el-pagination__total）
    //   `--` 双横线   = BEM 修饰符（el-button--primary），Element Plus 的按钮 type
    //                  变体全是这个形状；不放行就无法覆盖它的 type 级禁用底色
    // 放宽的只是"第三方前缀允许的形状"，本项目自己的语义类名仍是 kebab-case（check-spec 另有一道）
    'selector-class-pattern': '^[a-z][a-z0-9]*((-[a-z0-9]+)|(__[a-z0-9-]+)|(--[a-z0-9-]+))*$',
    'selector-max-id': 0,
    'selector-max-type': 0,
    // 嵌套与长度约束（VUE-CSS-001 嵌套 ≤3；复合选择器与特异性上限为工具约束，CSS 无嵌套时天然满足）
    'max-nesting-depth': 3,
    'selector-max-compound-selectors': 3,
    'selector-max-specificity': '0,3,0',
    // 缩写与零值（VUE-MACHINE-001）
    'length-zero-no-unit': true,
    'declaration-block-no-shorthand-property-overrides': true,
    // 每行一条声明（VUE-MACHINE-001）
    'declaration-block-single-line-max-declarations': 1,
    // Vue scoped 样式放行 :deep / :global / :slotted 标准伪类
    'selector-pseudo-class-no-unknown': [
      true,
      { ignorePseudoClasses: ['deep', 'global', 'slotted'] },
    ],
  },
  overrides: [
    {
      files: ['**/*.vue'],
      customSyntax: 'postcss-html',
    },
  ],
};
