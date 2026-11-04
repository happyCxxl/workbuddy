<template>
  <div v-if="props.open" class="composer-menu">
    <button
      v-for="(command, index) in props.commands"
      :key="command.name"
      type="button"
      class="composer-menu-item"
      :class="{ 'composer-menu-item-on': index === props.activeIndex }"
      @mousedown.prevent
      @click="emit('pick', command)"
    >
      <span class="composer-menu-name">{{ command.name }}</span>
      <span class="composer-menu-desc">
        {{ command.description }}{{ command.argsHint ? ` · ${command.argsHint}` : ''
        }}{{ command.kind === 'skill' ? ' · 技能' : '' }}
      </span>
    </button>
    <div v-if="props.commands.length === 0" class="composer-menu-empty">没有匹配的命令。</div>
  </div>
</template>

<script setup lang="ts">
import type { SlashCommand } from '@/types/assistant';

const props = defineProps<{
  open: boolean;
  commands: SlashCommand[];
  activeIndex: number;
}>();

const emit = defineEmits<{ pick: [command: SlashCommand] }>();
</script>

<style scoped lang="css">
.composer-menu {
  position: absolute;
  right: 14px;
  bottom: calc(100% + 6px);
  left: 14px;
  z-index: 30;
  max-height: 340px;
  overflow: auto;
  border: 1px solid var(--kb-line-2);
  background: var(--kb-bg-1);
  box-shadow: 0 12px 32px rgb(0 0 0 / 45%);
}

.composer-menu-item {
  display: grid;
  width: 100%;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 16px;
  align-items: center;
  padding: 8px 12px;
  border: 0;
  border-bottom: 1px solid rgb(34 224 255 / 6%);
  background: transparent;
  color: var(--kb-text-2);
  text-align: left;
  cursor: pointer;
}

.composer-menu-item:last-child {
  border-bottom: 0;
}

.composer-menu-item:hover,
.composer-menu-item-on {
  background: rgb(34 224 255 / 8%);
  box-shadow: inset 2px 0 0 var(--kb-primary);
  color: var(--kb-text-1);
}

.composer-menu-name {
  flex: none;
  font-family: ui-monospace, Consolas, monospace;
  font-size: 12px;
}

.composer-menu-desc {
  min-width: 0;
  overflow: hidden;
  color: var(--kb-text-3);
  font-size: 11px;
  text-align: right;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.composer-menu-empty {
  padding: 18px 12px;
  color: var(--kb-text-3);
  font-size: 12px;
  text-align: center;
}
</style>
