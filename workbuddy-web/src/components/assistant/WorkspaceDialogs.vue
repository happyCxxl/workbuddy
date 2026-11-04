<template>
  <el-dialog
    :model-value="props.workspaceOpen"
    title="新增工作区"
    width="520px"
    destroy-on-close
    @update:model-value="emit('update:workspaceOpen', $event)"
  >
    <el-form label-position="top">
      <el-form-item label="工作区目录（会话的文件操作限定在此目录）">
        <div class="workdir-row">
          <el-input
            :model-value="props.workspacePath"
            placeholder="请选择目录，或直接输入绝对路径"
            @update:model-value="emit('update:workspacePath', $event)"
          />
          <el-button @click="emit('open-dir-picker')">选择目录</el-button>
        </div>
        <div v-if="props.recentDirs.length > 0" class="recent-dirs">
          <span class="recent-label">最近：</span>
          <button
            v-for="dir in props.recentDirs"
            :key="dir"
            type="button"
            class="dir-chip"
            :class="{ 'dir-chip-on': props.workspacePath === dir }"
            :title="dir"
            @click="emit('update:workspacePath', dir)"
          >
            {{ baseName(dir) }}
          </button>
        </div>
      </el-form-item>
    </el-form>
    <p class="new-hint">
      工作区用于归置会话；创建后会自动打开一个新会话，之后也可在工作区点「＋」再建。
    </p>
    <template #footer>
      <el-button @click="emit('update:workspaceOpen', false)">取消</el-button>
      <el-button
        type="primary"
        :disabled="!props.workspacePath.trim()"
        @click="emit('create-workspace')"
      >
        创建工作区
      </el-button>
    </template>
  </el-dialog>

  <el-dialog
    :model-value="props.dirOpen"
    title="选择工作目录"
    width="560px"
    @update:model-value="emit('update:dirOpen', $event)"
  >
    <div class="dir-bar">
      <el-button size="small" :disabled="props.dirParent === null" @click="emit('go-up-dir')">
        上一级
      </el-button>
      <span class="dir-path" :title="props.dirPath || '此电脑'">{{
        props.dirPath || '此电脑'
      }}</span>
    </div>
    <div class="dir-list">
      <p v-if="props.dirEntries.length === 0" class="dir-empty">没有子目录。</p>
      <button
        v-for="entry in props.dirEntries"
        :key="entry.path"
        type="button"
        class="dir-item"
        @click="emit('enter-dir', entry.path)"
      >
        <span class="dir-icon">
          <svg
            width="14"
            height="14"
            viewBox="0 0 16 16"
            fill="none"
            stroke="currentColor"
            stroke-width="1.4"
          >
            <path d="M2 4.5h4l1.3 1.5H14v6a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1z" />
            <path d="M2 6h12" />
          </svg>
        </span>
        <span class="dir-name">{{ entry.name }}</span>
      </button>
    </div>
    <template #footer>
      <el-button @click="emit('update:dirOpen', false)">取消</el-button>
      <el-button type="primary" :disabled="!props.dirPath" @click="emit('choose-current-dir')">
        选择此目录
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import type { DirEntry } from '@/types/assistant';

const props = defineProps<{
  workspaceOpen: boolean;
  dirOpen: boolean;
  workspacePath: string;
  recentDirs: string[];
  dirPath: string;
  dirParent: string | null;
  dirEntries: DirEntry[];
}>();

const emit = defineEmits<{
  'update:workspaceOpen': [value: boolean];
  'update:dirOpen': [value: boolean];
  'update:workspacePath': [value: string];
  'open-dir-picker': [];
  'create-workspace': [];
  'go-up-dir': [];
  'enter-dir': [path: string];
  'choose-current-dir': [];
}>();

/** 路径末段：用于最近目录芯片的短名 */
function baseName(path: string): string {
  const parts = path.split(/[\\/]/).filter((part) => part.length > 0);
  return parts.at(-1) ?? path;
}
</script>

<style scoped lang="css">
.workdir-row {
  display: flex;
  gap: 8px;
  width: 100%;
}

.recent-dirs {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  margin-top: 8px;
}

.recent-label {
  color: var(--kb-text-3);
  font-size: 12px;
}

.dir-chip {
  max-width: 140px;
  overflow: hidden;
  padding: 2px 10px;
  border: 1px solid var(--kb-line);
  border-radius: 999px;
  background: var(--kb-surface);
  color: var(--kb-text-2);
  cursor: pointer;
  font-family: 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dir-chip-on {
  border-color: var(--kb-primary);
  color: var(--kb-primary);
}

.new-hint {
  margin: 0;
  color: var(--kb-text-3);
  font-size: 12px;
}

.dir-bar {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-bottom: 10px;
}

.dir-path {
  flex: 1;
  overflow: hidden;
  color: var(--kb-text-3);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dir-list {
  display: flex;
  max-height: 320px;
  flex-direction: column;
  gap: 2px;
  overflow: auto;
  padding: 4px;
  border: 1px solid var(--kb-line);
  border-radius: 10px;
}

.dir-empty {
  margin: 0;
  padding: 14px;
  color: var(--kb-text-3);
  font-size: 12px;
  text-align: center;
}

.dir-item {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 8px 10px;
  border: none;
  border-radius: 8px;
  background: none;
  color: var(--kb-text-1);
  cursor: pointer;
  font-family: 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif;
  font-size: 13px;
  text-align: left;
}

.dir-item:hover {
  background: rgb(255 255 255 / 5%);
}

.dir-icon {
  display: inline-flex;
  color: var(--kb-text-3);
}

.dir-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
