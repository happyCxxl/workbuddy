<template>
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 16 16"
    fill="none"
    stroke="currentColor"
    stroke-width="1.3"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <path v-for="(path, index) in paths" :key="index" :d="path" />
  </svg>
</template>

<script setup lang="ts">
import { computed } from 'vue';

/** 工具图标：按工具名映射到线性图标路径；未知工具用通用扳手。 */

const props = withDefaults(defineProps<{ name: string; size?: number }>(), { size: 12 });

const ICONS: Record<string, string[]> = {
  fs_read: ['M3 1.5h6l4 4v9H3z', 'M9 1.5v4h4'],
  fs_list: ['M2 4h5', 'M2 8h5', 'M2 12h5', 'M9 4h5', 'M9 8h5', 'M9 12h5'],
  fs_glob: ['M2 3h5l1.5 2H14v8H2z', 'M2 6h12'],
  fs_search: ['M7 2a5 5 0 1 0 0 10A5 5 0 0 0 7 2z', 'M11 11l3 3'],
  fs_write: ['M3 1.5h6l4 4v9H3z', 'M9 1.5v4h4', 'M5 10h6', 'M5 12.5h4'],
  fs_edit: ['M3 1.5h6l4 4v9H3z', 'M9 1.5v4h4', 'M5 11l3-3 1.5 1.5-3 3z'],
  fs_delete: ['M3 4h10', 'M6 4V2h4v2', 'M4.5 4l.5 10h6l.5-10'],
  shell: ['M2 3h12v10H2z', 'M4.5 6l2 2-2 2', 'M8.5 10h3'],
  web_search: ['M7 2a5 5 0 1 0 0 10A5 5 0 0 0 7 2z', 'M11 11l3 3'],
  web_fetch: ['M8 2v8', 'M4.5 6.5L8 10l3.5-3.5', 'M2.5 13h11'],
  todo: ['M2.5 3h11v10h-11z', 'M5 6h6', 'M5 9h4'],
};

const paths = computed<string[]>(
  () => ICONS[props.name] ?? ['M3 13l3-8 3 5 2-3 2 6', 'M2.5 13h11'],
);
</script>
