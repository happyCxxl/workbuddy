<template>
  <header class="parse-drawer-head">
    <div class="parse-drawer-title">
      <span class="parse-drawer-file">{{ fileName }}</span>
      <span class="parse-drawer-badge" :class="badgeClass">{{ statusText }}</span>
    </div>
    <button class="parse-drawer-x" type="button" @click="emit('close')">关闭</button>
  </header>
  <div class="parse-drawer-ident">
    <span class="parse-drawer-ident-item">开始 {{ startedAtText }}</span>
    <span class="parse-drawer-ident-item">耗时 {{ durationText }}</span>
    <span class="parse-drawer-ident-item">任务 {{ taskId }}</span>
  </div>

  <div class="parse-drawer-statbar">
    <span class="parse-drawer-conclusion" :class="conclusionClass">{{ conclusionText }}</span>
    <div class="parse-drawer-stats">
      <span v-for="item in statItems" :key="item.key" class="parse-drawer-stat">
        <span class="parse-drawer-stat-value">{{ item.value }}</span>
        {{ item.label }}
      </span>
    </div>
  </div>

  <section v-if="isFailed" class="parse-drawer-failbar">
    <span class="parse-drawer-error-code">{{ errorCode }}</span>
    <span class="parse-drawer-error-msg">{{ errorMsg }}</span>
    <span class="parse-drawer-advice">建议：{{ failAdvice }}</span>
  </section>
</template>

<script setup lang="ts">
import type { StageStatItem } from '@/types/pipeline-stage-view';

defineProps<{
  fileName: string;
  statusText: string;
  badgeClass: string;
  startedAtText: string;
  durationText: string;
  taskId: string;
  conclusionText: string;
  conclusionClass: string;
  statItems: StageStatItem[];
  isFailed: boolean;
  errorCode: string;
  errorMsg: string;
  failAdvice: string;
}>();

const emit = defineEmits<{ close: [] }>();
</script>

<style scoped>
.parse-drawer-head {
  display: flex;
  flex: none;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  padding: 14px 18px;
  border-bottom: 1px solid var(--kb-line);
}

.parse-drawer-title {
  display: flex;
  min-width: 0;
  gap: 10px;
  align-items: center;
}

.parse-drawer-file {
  overflow: hidden;
  font-size: 15px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 状态徽标：底色与文字色按状态 tone 给（is-* 规则在全局 chain-graph.css ——
   它们由计算属性动态给出，静态检查看不到 scoped 里的声明） */
.parse-drawer-badge {
  flex: none;
  padding: 2px 8px;
  border-radius: 99px;
  font-size: 11px;
  font-weight: 600;
}

.parse-drawer-x {
  flex: none;
  padding: 4px 10px;
  border: none;
  border-radius: 8px;
  background: var(--kb-surface);
  color: var(--kb-text-2);
  font-family: 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif;
  font-size: 12px;
  cursor: pointer;
}

.parse-drawer-x:hover {
  background: var(--kb-line-2);
}

.parse-drawer-ident {
  display: flex;
  flex: none;
  flex-wrap: wrap;
  gap: 16px;
  padding: 9px 18px;
  border-bottom: 1px solid var(--kb-line);
  color: var(--kb-text-3);
  font-size: 11px;
}

/* 统计条：固定分区，内容一行放不下就换行，自身不滚动 */
.parse-drawer-statbar {
  display: flex;
  flex: none;
  flex-wrap: wrap;
  gap: 8px 18px;
  align-items: baseline;
  padding: 10px 18px;
  border-bottom: 1px solid var(--kb-line);
}

/* 结论文案沿用状态色（规则同样在全局 chain-graph.css） */
.parse-drawer-conclusion {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
}

.parse-drawer-stats {
  display: flex;
  flex-wrap: wrap;
  gap: 18px;
}

.parse-drawer-stat {
  display: flex;
  gap: 5px;
  align-items: baseline;
  color: var(--kb-text-3);
  font-size: 11px;
}

.parse-drawer-stat-value {
  color: var(--kb-text-1);
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
  font-size: 13px;
  font-weight: 600;
}

/* 失败条：固定分区，把错误码 / 说明 / 建议压成一行一行的短句 */
.parse-drawer-failbar {
  display: flex;
  flex: none;
  flex-direction: column;
  margin: 10px 18px 0;
  padding: 12px 14px;
  gap: 6px;
  border: 1px solid rgb(248 113 113 / 28%);
  border-radius: 10px;
  background: rgb(248 113 113 / 8%);
}

.parse-drawer-error-code {
  margin: 0;
  color: var(--kb-danger);
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
  font-weight: 600;
}

.parse-drawer-error-msg {
  margin: 0;
  color: var(--kb-text-1);
  font-size: 12px;
}

.parse-drawer-advice {
  margin: 0;
  color: var(--kb-text-2);
  font-size: 12px;
  line-height: 1.6;
}
</style>
