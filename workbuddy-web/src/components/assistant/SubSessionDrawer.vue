<template>
  <el-drawer
    :model-value="visible"
    title="后台任务"
    size="46%"
    @update:model-value="emit('update:visible', $event)"
  >
    <div class="subs">
      <p class="subs-hint">
        子会话只完成给定任务：干净上下文（无人格、无记忆），权限沿用本会话，审批自动拒绝。
      </p>
      <p v-if="sessions.length === 0" class="trajectory-empty">还没有后台任务。</p>
      <div v-for="item in sessions" :key="item.id" class="sub-item">
        <div class="sub-head">
          <span class="sub-title">{{ item.title || '未命名任务' }}</span>
          <el-tag :type="subTagType(item.status)" effect="plain" size="small">
            {{ subStatusLabel(item.status) }}
          </el-tag>
          <span class="sub-time">{{ formatDateTime(item.created_at) }}</span>
        </div>
        <p class="sub-task">{{ item.task }}</p>
        <pre v-if="item.result" class="sub-result">{{ item.result }}</pre>
      </div>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import type { SubSessionItem } from '@/types/assistant';
import { formatDateTime } from '@/utils/date';

defineProps<{
  /** 是否显示抽屉 */
  visible: boolean;
  /** 后台任务列表 */
  sessions: SubSessionItem[];
}>();

const emit = defineEmits<{ 'update:visible': [visible: boolean] }>();

/** 后台任务状态中文名 */
function subStatusLabel(status: string): string {
  if (status === 'running') {
    return '运行中';
  }
  if (status === 'completed') {
    return '已完成';
  }
  return '失败';
}

/** 后台任务状态标签色 */
function subTagType(status: string): 'success' | 'danger' | 'info' {
  if (status === 'running') {
    return 'info';
  }
  return status === 'completed' ? 'success' : 'danger';
}
</script>

<style scoped lang="css">
.subs {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.subs-hint {
  margin: 0;
  color: var(--kb-text-3);
  font-size: 12px;
}

.sub-item {
  padding: 10px 12px;
  border: 1px solid var(--kb-line);
  border-radius: 10px;
  background: var(--kb-surface);
}

.sub-head {
  display: flex;
  gap: 8px;
  align-items: center;
}

.sub-title {
  font-size: 13px;
  font-weight: 600;
}

.sub-time {
  margin-left: auto;
  color: var(--kb-text-3);
  font-size: 11px;
}

.sub-task {
  margin: 6px 0;
  color: var(--kb-text-2);
  font-size: 12px;
  white-space: pre-wrap;
}

.sub-result {
  margin: 0;
  overflow-wrap: anywhere;
  color: var(--kb-text-3);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 11px;
  white-space: pre-wrap;
  word-break: break-all;
}

.trajectory-empty {
  color: var(--kb-text-3);
  font-size: 13px;
}
</style>
