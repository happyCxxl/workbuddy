<template>
  <div class="parse-drawer-tabfoot">
    <button
      class="parse-drawer-page-btn"
      type="button"
      :disabled="page <= 1 || loading"
      @click="emit('change', page - 1)"
    >
      上一页
    </button>
    <span class="parse-drawer-page-info">第 {{ page }} / {{ pageCount }} 页</span>
    <button
      class="parse-drawer-page-btn"
      type="button"
      :disabled="page >= pageCount || loading"
      @click="emit('change', page + 1)"
    >
      下一页
    </button>
    <span class="parse-drawer-page-info">共 {{ total }} 条 · 每页 {{ pageSize }}</span>
  </div>
</template>

<script setup lang="ts">
// 解析抽屉的分页脚：上一页 / 页码 / 下一页 / 总数
withDefaults(
  defineProps<{
    /** 当前页（从 1 开始） */
    page: number;
    /** 总页数 */
    pageCount: number;
    /** 总条数 */
    total: number;
    /** 是否正在加载 */
    loading?: boolean;
    /** 每页条数 */
    pageSize: number;
  }>(),
  { loading: false },
);

const emit = defineEmits<{ change: [page: number] }>();
</script>

<style scoped lang="css">
.parse-drawer-tabfoot {
  display: flex;
  flex: none;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  padding: 8px 14px;
  border-top: 1px solid var(--kb-line);
}

.parse-drawer-page-btn {
  padding: 4px 12px;
  border: 1px solid var(--kb-line-2);
  border-radius: 8px;
  background: var(--kb-surface);
  color: var(--kb-text-2);
  font-family: 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif;
  font-size: 12px;
  cursor: pointer;
}

.parse-drawer-page-btn:disabled {
  color: var(--kb-text-4);
  cursor: not-allowed;
}

.parse-drawer-page-btn:not(:disabled):hover {
  border-color: var(--kb-primary);
  color: var(--kb-primary);
}

.parse-drawer-page-info {
  color: var(--kb-text-3);
  font-size: 11px;
}
</style>
