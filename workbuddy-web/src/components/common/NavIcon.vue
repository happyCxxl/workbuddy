<template>
  <svg
    width="16"
    height="16"
    viewBox="0 0 16 16"
    fill="none"
    stroke="currentColor"
    stroke-width="1.4"
  >
    <path v-for="(d, index) in paths" :key="index" :d="d" stroke-linejoin="round" />
    <circle v-if="circle" cx="8" cy="5.4" r="2.6" />
  </svg>
</template>

<script setup lang="ts">
// 导航图标：按路径数组绘制同一套线性图标，可选叠加一个圆点（人物类图标用）
withDefaults(
  defineProps<{
    /** 图标路径集合：每条为一段 SVG path 的 d 属性 */
    paths: string[];
    /** 是否在图标上叠加一个圆点 */
    circle?: boolean;
  }>(),
  { circle: false },
);
</script>
