<template>
  <div class="composer">
    <ComposerQueue
      :items="queueItems"
      :drag-index="dragIndex"
      :drag-over-index="dragOverIndex"
      @drag-start="emit('queue-drag-start', $event)"
      @drag-over="emit('queue-drag-over', $event)"
      @drop="emit('queue-drop')"
      @drag-end="emit('queue-drag-end')"
      @edit="emit('queue-edit', $event)"
      @remove="emit('queue-remove', $event)"
    />
    <SlashMenu
      :open="menuOpen"
      :commands="filteredCommands"
      :active-index="slashIndex"
      @pick="pick"
    />
    <el-input
      :model-value="modelValue"
      type="textarea"
      :autosize="{ minRows: 3, maxRows: 8 }"
      resize="none"
      placeholder="发消息或创建任务 · / 命令 · Shift+Enter 换行"
      :disabled="!current"
      @update:model-value="onInput"
      @keydown="handleKeydown"
    />
    <div class="composer-row">
      <div class="composer-hints">
        <el-popover placement="top-start" width="220" trigger="click">
          <template #reference>
            <button class="hint-link" type="button" :disabled="!current">
              <svg
                class="hint-icon"
                width="12"
                height="12"
                viewBox="0 0 16 16"
                fill="none"
                stroke="currentColor"
                stroke-width="1.4"
              >
                <path d="M8 1.5l5 2.5v4c0 3-2.2 5.3-5 6.5-2.8-1.2-5-3.5-5-6.5v-4z" />
              </svg>
              权限 {{ presetLabel(preset) }}
            </button>
          </template>
          <div class="preset-box">
            <button
              v-for="option in PRESET_OPTIONS"
              :key="option.value"
              type="button"
              class="preset-item"
              :class="{ 'preset-item-on': preset === option.value }"
              @click="emit('change-preset', option.value)"
            >
              <span class="preset-name">{{ option.label }}</span>
              <span v-if="preset === option.value" class="preset-check">✓</span>
            </button>
          </div>
        </el-popover>
        <el-popover placement="top-start" width="280" trigger="click">
          <template #reference>
            <button class="hint-link" type="button" :disabled="!current">
              <svg
                class="hint-icon"
                width="12"
                height="12"
                viewBox="0 0 16 16"
                fill="none"
                stroke="currentColor"
                stroke-width="1.4"
              >
                <ellipse cx="8" cy="3.5" rx="5" ry="2" />
                <path d="M3 3.5v9c0 1.1 2.2 2 5 2s5-.9 5-2v-9" />
                <path d="M3 8c0 1.1 2.2 2 5 2s5-.9 5-2" />
              </svg>
              用量 {{ compactTokens(totalTokens) }} tok
            </button>
          </template>
          <UsagePopover
            title="Token 用量"
            :total="String(totalTokens)"
            :cache-rate="cacheRate"
            :cache-miss="usage?.prompt_cache_miss_tokens ?? 0"
            :cache-hit="usage?.prompt_cache_hit_tokens ?? 0"
            :completion="usage?.completion_tokens ?? 0"
          />
        </el-popover>
      </div>
      <el-select
        v-if="current"
        :model-value="modelId"
        class="composer-model"
        size="small"
        filterable
        placeholder="搜索模型"
        popper-class="model-select-popper"
        @change="emit('model-change', $event)"
      >
        <template #label="{ value }">
          <span class="model-label">{{ modelTitle(String(value)) }}</span>
        </template>
        <el-option-group v-for="group in modelGroups" :key="group.name" :label="group.name">
          <el-option
            v-for="model in group.models"
            :key="model.id"
            :label="model.display_name"
            :value="model.id"
          />
        </el-option-group>
      </el-select>
      <el-button v-if="running" type="danger" plain :loading="stopping" @click="emit('stop')">
        ■ 停止
      </el-button>
      <el-button
        v-else
        size="small"
        plain
        :disabled="!current || !modelValue.trim()"
        @click="emit('background')"
      >
        转后台
      </el-button>
      <el-button
        v-if="!running"
        type="primary"
        :disabled="!current || !modelValue.trim()"
        @click="emit('send')"
      >
        发送
      </el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';

import type { QueueItem } from '@/api/assistant';
import type { AvailableModel } from '@/api/model-admin';
import ComposerQueue from '@/components/assistant/ComposerQueue.vue';
import SlashMenu from '@/components/assistant/SlashMenu.vue';
import UsagePopover from '@/components/common/UsagePopover.vue';
import type {
  AssistantSession,
  PermissionPreset,
  SessionUsage,
  SlashCommand,
} from '@/types/assistant';

const props = defineProps<{
  modelValue: string;
  current: AssistantSession | null;
  running: boolean;
  stopping: boolean;
  queueItems: QueueItem[];
  dragIndex: number;
  dragOverIndex: number;
  commands: SlashCommand[];
  preset: PermissionPreset;
  totalTokens: number;
  cacheRate: number;
  usage: SessionUsage | null;
  modelId: string;
  models: AvailableModel[];
}>();

const emit = defineEmits<{
  'update:modelValue': [value: string];
  'need-skills': [];
  'pick-command': [command: SlashCommand];
  send: [];
  stop: [];
  background: [];
  interrupt: [];
  'change-preset': [value: string | number | boolean | undefined];
  'model-change': [value: string];
  'queue-drag-start': [index: number];
  'queue-drag-over': [index: number];
  'queue-drop': [];
  'queue-drag-end': [];
  'queue-edit': [item: QueueItem];
  'queue-remove': [item: QueueItem];
}>();

/** 权限档位选项（对齐 DSH：能力级三档，上下排列） */
const PRESET_OPTIONS: { value: PermissionPreset; label: string }[] = [
  { value: 'read-only', label: '仅可查看' },
  { value: 'workspace-write', label: '工作区内修改' },
  { value: 'full-access', label: '完全权限' },
];

/** 命令菜单是否打开 */
const menuOpen = ref(false);
/** 命令过滤词（不含前导 `/`） */
const query = ref('');
/** 当前高亮的命令下标 */
const slashIndex = ref(0);

/** 过滤后的命令列表 */
const filteredCommands = computed(() =>
  props.commands.filter((command) => command.name.slice(1).startsWith(query.value)),
);

/** 档位中文名（对齐 DSH：仅可查看 / 工作区内修改 / 完全权限；兼容旧值） */
function presetLabel(value: string): string {
  if (value === 'read-only' || value === 'strict') {
    return '仅可查看';
  }
  if (value === 'full-access') {
    return '完全权限';
  }
  return '工作区内修改';
}

/** token 数缩写：1.2M / 760k */
function compactTokens(value: number): string {
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(1)}M`;
  if (value >= 1000) return `${String(Math.round(value / 1000))}k`;
  return String(value);
}

/** 模型展示：厂商 · 模型名 */
function modelTitle(modelId: string): string {
  const model = props.models.find((item) => item.id === modelId);
  if (!model) {
    return '';
  }
  return `${model.vendor_name || model.vendor_code} · ${model.display_name}`;
}

/** 按厂商分组：与 DSH 的账号分组一致 */
const modelGroups = computed(() => {
  const groups = new Map<string, AvailableModel[]>();
  for (const model of props.models) {
    const name = model.vendor_name || model.vendor_code || '其他';
    const list = groups.get(name) ?? [];
    list.push(model);
    groups.set(name, list);
  }
  return Array.from(groups, ([name, models]) => ({ name, models }));
});

/** 输入变化：/ 开头且未进入参数时打开命令补全 */
function onInput(value: string): void {
  emit('update:modelValue', value);
  if (value.startsWith('/') && !value.includes(' ')) {
    menuOpen.value = true;
    query.value = value.slice(1);
    slashIndex.value = 0;
    emit('need-skills');
    return;
  }
  menuOpen.value = false;
}

/** 选中命令：关闭菜单并交给父级处理 */
function pick(command: SlashCommand): void {
  menuOpen.value = false;
  emit('pick-command', command);
}

/** composer 键盘：菜单优先，其次才是发送 */
function handleKeydown(raw: Event): void {
  if (!(raw instanceof KeyboardEvent)) {
    return;
  }
  const event = raw;
  if (event.isComposing) {
    return;
  }
  if (menuOpen.value) {
    const count = filteredCommands.value.length;
    if (event.key === 'ArrowDown' && count > 0) {
      event.preventDefault();
      slashIndex.value = (slashIndex.value + 1) % count;
    } else if (event.key === 'ArrowUp' && count > 0) {
      event.preventDefault();
      slashIndex.value = (slashIndex.value - 1 + count) % count;
    } else if ((event.key === 'Enter' || event.key === 'Tab') && count > 0) {
      event.preventDefault();
      pick(filteredCommands.value[slashIndex.value]);
    } else if (event.key === 'Escape') {
      event.preventDefault();
      menuOpen.value = false;
    }
    return;
  }
  if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
    event.preventDefault();
    emit('interrupt');
    return;
  }
  if (event.key === 'Escape' && props.running) {
    event.preventDefault();
    emit('stop');
    return;
  }
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault();
    emit('send');
  }
}
</script>

<style scoped>
.composer {
  position: relative;
  display: flex;
  flex: none;
  flex-direction: column;
  gap: 8px;
  padding: 12px 14px 14px;
  border-top: 1px solid var(--kb-line);
}

.composer-row {
  display: flex;
  gap: 10px;
  align-items: center;
}

.composer-hints {
  display: flex;
  flex: 1;
  gap: 14px;
  align-items: center;
  color: var(--kb-text-3);
  font-size: 12px;
}

.hint-link {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  padding: 0;
  border: none;
  background: none;
  color: var(--kb-primary);
  cursor: pointer;
  font-family: 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif;
  font-size: 12px;
}

.hint-icon {
  width: 12px;
  height: 12px;
}

.preset-box {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.preset-item {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 7px 8px;
  border: none;
  border-radius: 6px;
  color: var(--kb-text-2);
  background: transparent;
  cursor: pointer;
  text-align: left;
  font-size: 12px;
}

.preset-item:hover {
  background: rgb(255 255 255 / 5%);
}

.preset-item-on {
  background: rgb(52 211 153 / 10%);
}

.preset-name {
  flex: 1;
}

.preset-check {
  color: var(--kb-primary-2);
  font-size: 12px;
}

.composer-model {
  width: 210px;
  flex: none;
}
</style>
