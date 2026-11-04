<template>
  <div
    class="sessions-panel"
    :class="{ 'is-collapsed': props.collapsed, 'is-switching': props.switching }"
  >
    <div class="sessions-head">
      <span class="sessions-title">工作区</span>
      <span class="page-spacer"></span>
      <button
        class="head-icon"
        :class="{ 'head-icon-on': props.searchVisible }"
        type="button"
        title="搜索工作区"
        @click="emit('update:searchVisible', !props.searchVisible)"
      >
        <svg
          width="16"
          height="16"
          viewBox="0 0 16 16"
          fill="none"
          stroke="currentColor"
          stroke-width="1.5"
        >
          <circle cx="7" cy="7" r="4.2" />
          <path d="m10.2 10.2 3 3" />
        </svg>
      </button>
      <button
        class="head-icon"
        :class="{ 'head-icon-on': props.onlyWithSessions }"
        type="button"
        title="仅显示有会话的工作区"
        @click="emit('update:onlyWithSessions', !props.onlyWithSessions)"
      >
        <svg
          width="16"
          height="16"
          viewBox="0 0 16 16"
          fill="none"
          stroke="currentColor"
          stroke-width="1.5"
        >
          <path d="M2.5 4h11M4.5 8h7M6.5 12h3" />
        </svg>
      </button>
      <button class="head-icon" type="button" title="新增工作区" @click="emit('new-workspace')">
        <svg
          width="16"
          height="16"
          viewBox="0 0 16 16"
          fill="none"
          stroke="currentColor"
          stroke-width="1.4"
        >
          <path d="M2 4.5h4l1.3 1.5H13v6a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1z" />
          <path d="M10 5.6v4.2M7.9 7.7h4.2" />
        </svg>
      </button>
    </div>
    <div v-if="props.searchVisible" class="workspace-search">
      <el-input
        :model-value="props.workspaceKeyword"
        size="small"
        placeholder="搜索工作区名称或路径"
        clearable
        @update:model-value="emit('update:workspaceKeyword', $event)"
      />
    </div>
    <div class="sessions-list">
      <div v-for="group in props.groups" :key="group.key" class="session-group">
        <div class="group-head" @click="emit('toggle-group', group.key)">
          <span class="group-folder">
            <svg
              width="18"
              height="18"
              viewBox="0 0 16 16"
              fill="none"
              stroke="currentColor"
              stroke-width="1.4"
            >
              <path d="M2 4.5h4l1.3 1.5H14v6a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1z" />
              <path d="M2 6h12" />
            </svg>
          </span>
          <span
            class="group-arrow"
            :class="{ 'group-arrow-open': !props.collapsedGroups[group.key] }"
            >▸</span
          >
          <span class="group-name" :title="group.path">{{ group.name }}</span>
          <span v-if="group.workspaceId" class="group-actions" @click.stop>
            <el-dropdown trigger="click" @command="emit('group-command', $event, group)">
              <button class="group-icon" type="button" title="更多">⋯</button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="copy">复制路径</el-dropdown-item>
                  <el-dropdown-item command="rename">重命名</el-dropdown-item>
                  <el-dropdown-item command="delete-workspace">删除工作区</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
            <button
              class="group-icon group-add"
              type="button"
              title="在该工作区新建会话"
              @click.stop="emit('new-session', group)"
            >
              ＋
            </button>
          </span>
        </div>
        <div v-show="!props.collapsedGroups[group.key]" class="group-items">
          <p v-if="group.items.length === 0" class="group-empty">暂无会话</p>
          <button
            v-for="item in group.items"
            :key="item.id"
            type="button"
            class="session-item"
            :class="{ 'session-item-on': item.id === props.currentId }"
            @click="emit('select', item)"
          >
            <span class="session-title">{{ item.title || '未命名会话' }}</span>
            <span class="session-time">{{ formatDateTime(item.updated_at) }}</span>
            <span class="session-rename" title="重命名" @click.stop="emit('rename', item)">✎</span>
            <span class="session-delete" title="删除会话" @click.stop="emit('delete', item)"
              >✕</span
            >
          </button>
        </div>
      </div>
      <p v-if="props.empty" class="session-empty">暂无工作区</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { formatDateTime } from '@/utils/date';
import type { AssistantSession, SessionGroup } from '@/types/assistant';

const props = defineProps<{
  groups: SessionGroup[];
  currentId: string | null;
  collapsedGroups: Record<string, boolean>;
  searchVisible: boolean;
  onlyWithSessions: boolean;
  workspaceKeyword: string;
  empty: boolean;
  collapsed: boolean;
  switching: boolean;
}>();

const emit = defineEmits<{
  'update:searchVisible': [value: boolean];
  'update:onlyWithSessions': [value: boolean];
  'update:workspaceKeyword': [value: string];
  'toggle-group': [key: string];
  'group-command': [command: unknown, group: SessionGroup];
  'new-session': [group: SessionGroup];
  select: [item: AssistantSession];
  rename: [item: AssistantSession];
  delete: [item: AssistantSession];
  'new-workspace': [];
}>();
</script>

<style scoped lang="css">
/* 面板容器：栅格列宽由父级 .chat-wrap 决定，这里只管自身布局与外观 */
.sessions-panel {
  display: flex;
  min-height: 0;
  flex-direction: column;
  overflow: hidden;
  min-width: 240px;
  border-right: 1px solid var(--kb-line);
  background: linear-gradient(180deg, var(--kb-bg-1), #050a12);
  transition: opacity 0.8s ease;
}

.sessions-panel.is-switching {
  opacity: 0;
}

.sessions-panel.is-collapsed {
  border-right-color: transparent;
  opacity: 0;
  pointer-events: none;
}

@media (prefers-reduced-motion: reduce) {
  .sessions-panel {
    transition: none;
  }
}

.sessions-head {
  display: flex;
  flex: none;
  gap: 2px;
  align-items: center;
  padding: 12px 12px 12px 14px;
  border-bottom: 1px solid var(--kb-line);
}

.sessions-title {
  color: var(--kb-text-2);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 13px;
  font-weight: 500;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

/* 与聊天页头部共用图标按钮外观；组件内需自带一份（scoped 不跨组件生效） */
.head-icon {
  display: inline-flex;
  flex: none;
  width: 28px;
  height: 28px;
  align-items: center;
  justify-content: center;
  padding: 0;
  border: none;
  border-radius: 6px;
  background: none;
  color: var(--kb-text-3);
  cursor: pointer;
}

.head-icon:hover {
  background: rgb(34 224 255 / 8%);
  color: var(--kb-primary);
}

.head-icon-on {
  color: var(--kb-primary);
}

.workspace-search {
  flex: none;
  padding: 8px 10px;
  border-bottom: 1px solid var(--kb-line);
}

.sessions-list {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 6px 6px 12px;
}

/* 工作区行：34px；文件夹图标悬停/选中切换为展开箭头（DSH 同款交互） */
.group-head {
  display: flex;
  height: 44px;
  align-items: center;
  gap: 8px;
  padding: 0 8px 0 10px;
  border-radius: 8px;
  color: var(--kb-text-2);
  cursor: pointer;
  user-select: none;
}

.group-head:hover {
  background: rgb(34 224 255 / 5%);
}

.group-folder {
  display: inline-flex;
  flex: none;
  width: 20px;
  align-items: center;
  justify-content: center;
  color: var(--kb-primary);
}

.group-arrow {
  display: none;
  flex: none;
  width: 16px;
  color: var(--kb-text-3);
  font-size: 9px;
  text-align: center;
  transition: transform 150ms ease;
}

.group-arrow-open {
  transform: rotate(90deg);
}

.group-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  font-size: 16px;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.group-actions {
  display: none;
  flex: none;
  align-items: center;
  gap: 2px;
}

.group-head:hover .group-actions {
  display: inline-flex;
}

.group-icon {
  display: inline-flex;
  width: 20px;
  height: 20px;
  align-items: center;
  justify-content: center;
  padding: 0;
  border: none;
  border-radius: 5px;
  background: none;
  color: var(--kb-text-3);
  cursor: pointer;
  font-size: 12px;
  line-height: 20px;
  text-align: center;
}

.group-icon:hover {
  background: rgb(255 255 255 / 10%);
  color: var(--kb-text-1);
}

.group-add:hover {
  color: var(--kb-primary);
}

.group-empty {
  margin: 0;
  padding: 2px 8px 6px;
  color: var(--kb-text-3);
  font-size: 12px;
}

.group-items {
  display: flex;
  flex-direction: column;
  padding-left: 22px;
}

/* 会话行：名称在上、时间在下的两行结构 */
.session-item {
  position: relative;
  display: flex;
  height: auto;
  min-height: 48px;
  width: 100%;
  flex-direction: column;
  align-items: flex-start;
  justify-content: center;
  gap: 3px;
  padding: 8px 34px 8px 14px;
  border: none;
  clip-path: polygon(6px 0, 100% 0, 100% calc(100% - 6px), calc(100% - 6px) 100%, 0 100%, 0 6px);
  background: none;
  color: var(--kb-text-1);
  cursor: pointer;
  font-family: 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif;
  text-align: left;
}

.session-item:hover {
  background: rgb(34 224 255 / 5%);
}

.session-item-on {
  background: linear-gradient(90deg, rgb(34 224 255 / 14%), rgb(34 224 255 / 3%) 72%, transparent);
  box-shadow: inset 0 0 0 1px var(--kb-line);
}

.session-item-on::before {
  position: absolute;
  top: 5px;
  bottom: 5px;
  left: 0;
  width: 2px;
  background: var(--kb-primary);
  box-shadow: 0 0 12px var(--kb-primary);
  content: '';
}

.session-item-on:hover {
  background: var(--kb-tint);
}

.session-title {
  flex: none;
  width: 100%;
  min-width: 0;
  overflow: hidden;
  font-size: 14px;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-time {
  display: block;
  flex: none;
  color: var(--kb-text-3);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 11px;
  letter-spacing: 0.04em;
}

.session-rename {
  position: absolute;
  top: 6px;
  right: 22px;
  display: none;
  width: 18px;
  color: var(--kb-text-3);
  font-size: 11px;
  text-align: center;
}

.session-rename:hover {
  color: var(--kb-primary);
}

.session-delete {
  position: absolute;
  top: 6px;
  right: 4px;
  display: none;
  width: 18px;
  color: var(--kb-text-3);
  font-size: 11px;
  text-align: center;
}

.session-delete:hover {
  color: var(--kb-danger);
}

.session-item:hover .session-rename,
.session-item:hover .session-delete {
  display: block;
}

.session-group {
  margin-bottom: 2px;
}

.session-empty {
  padding: 18px 12px;
  color: var(--kb-text-3);
  font-size: 13px;
  text-align: center;
}
</style>
