<template>
  <div v-if="props.items.length > 0" class="queue-panel">
    <div class="queue-head">
      <span>排队中</span>
      <span>{{ props.items.length }}</span>
    </div>
    <div
      v-for="(item, index) in props.items"
      :key="item.id"
      class="queue-row"
      :class="{
        'queue-row-dragging': props.dragIndex === index,
        'queue-row-over': props.dragOverIndex === index && props.dragIndex !== index,
      }"
      draggable="true"
      @dragstart="emit('drag-start', index)"
      @dragover.prevent="emit('drag-over', index)"
      @drop.prevent="emit('drop')"
      @dragend="emit('drag-end')"
    >
      <span class="queue-grip" aria-hidden="true">⠿</span>
      <span class="queue-content">{{ item.content }}</span>
      <span class="queue-actions">
        <button type="button" class="queue-action" @click="emit('edit', item)">编辑</button>
        <button type="button" class="queue-action" @click="emit('remove', item)">撤回</button>
      </span>
    </div>
  </div>
</template>

<script setup lang="ts">
import type { QueueItem } from '@/api/assistant';

const props = defineProps<{
  items: QueueItem[];
  dragIndex: number;
  dragOverIndex: number;
}>();

const emit = defineEmits<{
  'drag-start': [index: number];
  'drag-over': [index: number];
  drop: [];
  'drag-end': [];
  edit: [item: QueueItem];
  remove: [item: QueueItem];
}>();
</script>

<style scoped lang="css">
.queue-panel {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 5px 6px 6px;
  border: 1px solid var(--kb-line);
  background: rgb(34 224 255 / 3%);
}

.queue-head {
  display: flex;
  justify-content: space-between;
  padding: 1px 4px 5px;
  color: var(--kb-text-3);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 10px;
  letter-spacing: 0.08em;
}

.queue-row {
  display: flex;
  min-height: 28px;
  gap: 6px;
  align-items: center;
  padding: 3px 4px;
  border-top: 1px solid rgb(34 224 255 / 5%);
  cursor: grab;
}

.queue-row:first-of-type {
  border-top: 0;
}

.queue-row-dragging {
  opacity: 0.35;
}

.queue-row-over {
  box-shadow: inset 0 2px 0 var(--kb-primary);
}

.queue-grip {
  flex: none;
  color: var(--kb-text-3);
  font-size: 13px;
  line-height: 1;
  letter-spacing: 1px;
}

.queue-content {
  min-width: 0;
  flex: 1;
  overflow: hidden;
  color: var(--kb-text-2);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.queue-actions {
  display: flex;
  flex: none;
  gap: 2px;
  opacity: 0;
  transition: opacity 0.12s ease;
}

.queue-row:hover .queue-actions {
  opacity: 1;
}

.queue-action {
  padding: 1px 4px;
  border: 0;
  background: transparent;
  color: var(--kb-text-3);
  font-size: 10px;
  cursor: pointer;
}

.queue-action:hover {
  color: var(--kb-primary);
}
</style>
