<template>
  <div class="page assistant-page">
    <div class="chat-wrap" :class="{ 'sessions-collapsed': !sessionsOpen, switching }">
      <!-- 工作区列表 -->
      <SessionSidebar
        v-model:search-visible="searchVisible"
        v-model:only-with-sessions="onlyWithSessions"
        v-model:workspace-keyword="workspaceKeyword"
        :groups="sessionGroups"
        :current-id="currentId"
        :collapsed-groups="collapsedGroups"
        :collapsed="!sessionsOpen"
        :switching="switching"
        :empty="workspaces.length === 0 && sessions.length === 0"
        @toggle-group="toggleGroup"
        @group-command="handleGroupCommand"
        @new-session="handleNewSession"
        @select="selectSession"
        @rename="handleRename"
        @delete="handleDeleteSession"
        @new-workspace="openWorkspaceDialog"
      />

      <!-- 切换会话栏时：发光扫描线从左到右擦除 / 从右到左重绘网格 -->
      <div v-show="switching" ref="wipeEl" class="grid-wipe" aria-hidden="true">
        <span class="wipe-edge"></span>
      </div>

      <!-- 对话区 -->
      <div class="chat-panel">
        <ChatHeader
          :sessions-open="sessionsOpen"
          :title="current?.title || '选择或新建一个会话'"
          :work-dir="current ? current.work_dir : null"
          :has-session="Boolean(current)"
          :running-sub-count="runningSubCount"
          :running="running"
          :step-label="stepLabel"
          @toggle-sessions="toggleSessions"
          @open-trajectory="openTrajectory"
          @open-sub-sessions="openSubSessions"
        />

        <div ref="messageBox" class="messages">
          <ChatTurnList
            :turns="pagedTurns"
            :hidden-turns="hiddenTurns"
            :turn-meta="turnMeta"
            :expanded-keys="expandedTurns"
            :checkpoints="checkpoints"
            @show-earlier="showEarlier"
            @toggle-process="toggleProcess"
            @decide-permission="decidePermission"
            @rewind="rewindTurn"
          />
        </div>

        <div class="composer">
          <ComposerQueue
            :items="queueItems"
            :drag-index="dragIndex"
            :drag-over-index="dragOverIndex"
            @drag-start="onQueueDragStart"
            @drag-over="onQueueDragOver"
            @drop="onQueueDrop"
            @drag-end="onQueueDragEnd"
            @edit="editQueueItem"
            @remove="removeQueueItem"
          />
          <SlashMenu
            :open="composerMenuOpen"
            :commands="filteredSlashCommands"
            :active-index="slashIndex"
            @pick="pickSlashCommand"
          />
          <el-input
            v-model="input"
            type="textarea"
            :autosize="{ minRows: 3, maxRows: 8 }"
            resize="none"
            placeholder="发消息或创建任务 · / 命令 · Shift+Enter 换行"
            :disabled="!current"
            @input="onComposerInput"
            @keydown="handleComposerKeydown"
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
                    权限 {{ presetLabel(currentPreset) }}
                  </button>
                </template>
                <div class="preset-box">
                  <button
                    v-for="option in PRESET_OPTIONS"
                    :key="option.value"
                    type="button"
                    class="preset-item"
                    :class="{ 'preset-item-on': currentPreset === option.value }"
                    @click="changePreset(option.value)"
                  >
                    <span class="preset-name">{{ option.label }}</span>
                    <span v-if="currentPreset === option.value" class="preset-check">✓</span>
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
                  :cache-rate="cacheRate ?? 0"
                  :cache-miss="usage?.prompt_cache_miss_tokens ?? 0"
                  :cache-hit="usage?.prompt_cache_hit_tokens ?? 0"
                  :completion="usage?.completion_tokens ?? 0"
                />
              </el-popover>
            </div>
            <el-select
              v-if="current"
              v-model="selectedModelId"
              class="composer-model"
              size="small"
              filterable
              placeholder="搜索模型"
              popper-class="model-select-popper"
              @change="handleModelChange"
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
            <el-button v-if="running" type="danger" plain :loading="stopping" @click="handleStop">
              ■ 停止
            </el-button>
            <el-button
              v-else
              size="small"
              plain
              :disabled="!current || !input.trim()"
              @click="handleBackground"
            >
              转后台
            </el-button>
            <el-button
              v-if="!running"
              type="primary"
              :disabled="!current || !input.trim()"
              @click="handleSend"
            >
              发送
            </el-button>
          </div>
        </div>
      </div>
    </div>

    <WorkspaceDialogs
      v-model:workspace-open="workspaceDialogVisible"
      v-model:dir-open="dirDialogVisible"
      v-model:workspace-path="workspacePath"
      :recent-dirs="recentDirs"
      :dir-path="dirPath"
      :dir-parent="dirParent"
      :dir-entries="dirEntries"
      @open-dir-picker="openDirPicker"
      @create-workspace="handleCreateWorkspace"
      @go-up-dir="goUpDir"
      @enter-dir="enterDir"
      @choose-current-dir="chooseCurrentDir"
    />

    <TrajectoryDrawer
      v-model:visible="trajectoryVisible"
      :events="trajectoryEvents"
      :model-calls="modelCalls"
    />

    <SubSessionDrawer v-model:visible="subVisible" :sessions="subSessions" />
  </div>
</template>

<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus';
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue';

import {
  addAssistantSession,
  addSessionQueue,
  addSubSession,
  addTurnResume,
  addTurnStream,
  addWorkspace,
  deleteAssistantSession,
  deleteSessionQueue,
  deleteWorkspace,
  getAssistantSessions,
  getSessionCalls,
  getSessionCheckpoints,
  getSessionEvents,
  getSessionQueue,
  getSessionSkills,
  getSessionUsage,
  getSubSessions,
  getWorkspaceDirs,
  getWorkspaces,
  updateCheckpointRewind,
  updateAssistantSession,
  updateSessionModel,
  updateSessionPermission,
  updateSessionQueue,
  updateSessionQueueOrder,
  updateTurnStop,
  updateWorkspace,
  type QueueItem,
  type SessionCheckpoint,
  type SkillItem,
} from '@/api/assistant';
import { getAvailableModels, type AvailableModel } from '@/api/model-admin';
import type {
  AssistantSession,
  DirEntry,
  ModelCallItem,
  PermissionPreset,
  SessionEventItem,
  SessionGroup,
  SessionUsage,
  SlashCommand,
  SubSessionItem,
  Workspace,
} from '@/types/assistant';
import type { ChatMessage, ToolLine, TurnUsage, TurnView } from '@/types/chat';
import { durationLabel, formatClock } from '@/utils/chat-format';
import ChatHeader from '@/components/assistant/ChatHeader.vue';
import ChatTurnList from '@/components/assistant/ChatTurnList.vue';
import ComposerQueue from '@/components/assistant/ComposerQueue.vue';
import SessionSidebar from '@/components/assistant/SessionSidebar.vue';
import SlashMenu from '@/components/assistant/SlashMenu.vue';
import SubSessionDrawer from '@/components/assistant/SubSessionDrawer.vue';
import TrajectoryDrawer from '@/components/assistant/TrajectoryDrawer.vue';
import UsagePopover from '@/components/common/UsagePopover.vue';
import WorkspaceDialogs from '@/components/assistant/WorkspaceDialogs.vue';

const sessions = ref<AssistantSession[]>([]);
const currentId = ref<string>('');
const current = ref<AssistantSession | null>(null);
const chatModels = ref<AvailableModel[]>([]);
const selectedModelId = ref('');

watch(current, (value) => {
  selectedModelId.value = value?.model_id ?? '';
});

async function handleModelChange(value: string): Promise<void> {
  if (!current.value) {
    return;
  }
  await updateSessionModel(current.value.id, value);
  current.value.model_id = value;
  ElMessage.success('模型已切换');
}

/** 模型展示：厂商 · 模型名 */
function modelTitle(modelId: string): string {
  const model = chatModels.value.find((item) => item.id === modelId);
  if (!model) {
    return '';
  }
  return `${model.vendor_name || model.vendor_code} · ${model.display_name}`;
}

/** 按厂商分组：与 DSH 的账号分组一致 */
const modelGroups = computed(() => {
  const groups = new Map<string, AvailableModel[]>();
  for (const model of chatModels.value) {
    const name = model.vendor_name || model.vendor_code || '其他';
    const list = groups.get(name) ?? [];
    list.push(model);
    groups.set(name, list);
  }
  return Array.from(groups, ([name, models]) => ({ name, models }));
});
/** 草稿会话：新建工作区/点「＋」时自动创建，未发消息就切走即丢弃（对齐 DSH） */
const draftSessionId = ref<string | null>(null);
const messages = ref<ChatMessage[]>([]);
/** 上一步以工具调用收尾：下一个增量事件开启新的步骤块，保证"思考 → 工具 → 思考"的循环可见 */
let awaitingNextStep = false;
let liveTurnSeq: number | undefined;
const input = ref('');
const runningSessionId = ref('');
const running = computed(() => runningSessionId.value === currentId.value);
const stopping = ref(false);
const activeTurnSessionId = ref('');
let stopTimer: number | undefined;
let skillTimer: number | undefined;
const approvalPending = ref(false);
const queueItems = ref<QueueItem[]>([]);
const dragIndex = ref(-1);
const dragOverIndex = ref(-1);
const builtinSlashCommands: SlashCommand[] = [
  { name: '/reload-skills', description: '重新加载技能目录', kind: 'builtin' },
];
const skillCommands = ref<SlashCommand[]>([]);
const skillsLoadedFor = ref('');
const slashCommands = computed<SlashCommand[]>(() => [
  ...builtinSlashCommands,
  ...skillCommands.value,
]);
const slashOpen = ref(false);
const slashQuery = ref('');
const slashIndex = ref(0);
const checkpoints = ref<SessionCheckpoint[]>([]);
const filteredSlashCommands = computed(() =>
  slashCommands.value.filter((command) => command.name.slice(1).startsWith(slashQuery.value)),
);
const composerMenuOpen = computed(() => slashOpen.value);
/** 过程组展开状态：完成的轮次默认折叠，运行中的轮次强制展开 */
const expandedTurns = ref<Set<string>>(new Set());
const stepLabel = ref('1');
const messageBox = ref<HTMLElement | null>(null);
const workspaceDialogVisible = ref(false);
const workspacePath = ref('');
const workspaces = ref<Workspace[]>([]);
const searchVisible = ref(false);

/** 会话栏展开状态（持久化） */
const sessionsOpen = ref(localStorage.getItem('assistant.sessionsOpen') !== '0');

/** 切换中：先淡出，改布局，再淡入，避免正文在动画里逐帧重排 */
const switching = ref(false);

/** 扫描擦除层 */
const wipeEl = ref<HTMLElement | null>(null);

/** 亮网格扫描：崩解时亮网格从左到右被擦掉，重组时从右到左重绘 */
function runWipe(mode: 'break' | 'assemble'): void {
  const el = wipeEl.value;
  if (!el) {
    return;
  }
  const edge = el.querySelector('.wipe-edge');
  const shrink = [{ clipPath: 'inset(0 0 0 0%)' }, { clipPath: 'inset(0 0 0 100%)' }];
  const grow = [{ clipPath: 'inset(0 0 0 100%)' }, { clipPath: 'inset(0 0 0 0%)' }];
  const edgeMove =
    mode === 'break' ? [{ left: '0%' }, { left: '100%' }] : [{ left: '100%' }, { left: '0%' }];
  el.animate(mode === 'break' ? shrink : grow, {
    duration: 800,
    easing: 'linear',
    fill: 'forwards',
  });
  edge?.animate(edgeMove, { duration: 800, easing: 'linear', fill: 'forwards' });
}

/** 收起 / 展开会话栏：淡出 + 网格扫描 → 换布局 → 淡入 */
function toggleSessions(): void {
  if (switching.value) {
    return;
  }
  switching.value = true;
  runWipe(sessionsOpen.value ? 'break' : 'assemble');
  window.setTimeout(() => {
    sessionsOpen.value = !sessionsOpen.value;
    localStorage.setItem('assistant.sessionsOpen', sessionsOpen.value ? '1' : '0');
    window.requestAnimationFrame(() => {
      switching.value = false;
    });
  }, 850);
}
const workspaceKeyword = ref('');
const onlyWithSessions = ref(false);
const usage = ref<SessionUsage | null>(null);
const trajectoryVisible = ref(false);
const trajectoryEvents = ref<SessionEventItem[]>([]);
const modelCalls = ref<ModelCallItem[]>([]);
const subVisible = ref(false);
const subSessions = ref<SubSessionItem[]>([]);
const collapsedGroups = ref<Record<string, boolean>>({});
const dirDialogVisible = ref(false);
const dirPath = ref('');
const dirParent = ref<string | null>(null);
const dirEntries = ref<DirEntry[]>([]);
let subTimer: number | null = null;

/** 工作区分组：已有工作区（含空工作区）+ 旧会话按目录兜底分组 */
const sessionGroups = computed<SessionGroup[]>(() => {
  const groups: SessionGroup[] = workspaces.value.map((workspace) => ({
    key: `ws:${workspace.id}`,
    name: workspace.name,
    path: workspace.path,
    workspaceId: workspace.id,
    items: [],
  }));
  const byWorkspace = new Map(groups.map((group) => [group.workspaceId, group]));
  const legacyGroups = new Map<string, SessionGroup>();
  for (const item of sessions.value) {
    const workspaceGroup = item.workspace_id ? byWorkspace.get(item.workspace_id) : undefined;
    if (workspaceGroup) {
      workspaceGroup.items.push(item);
      continue;
    }
    const key = `dir:${item.work_dir}`;
    const legacy = legacyGroups.get(key) ?? {
      key,
      name: baseName(item.work_dir),
      path: item.work_dir,
      workspaceId: null,
      items: [],
    };
    legacy.items.push(item);
    legacyGroups.set(key, legacy);
  }
  const all = [...groups, ...legacyGroups.values()];
  for (const group of all) {
    group.items.sort((left, right) => right.updated_at.localeCompare(left.updated_at));
  }
  const keyword = workspaceKeyword.value.trim().toLowerCase();
  return all.filter((group) => {
    if (onlyWithSessions.value && group.items.length === 0) {
      return false;
    }
    if (!keyword) {
      return true;
    }
    return group.name.toLowerCase().includes(keyword) || group.path.toLowerCase().includes(keyword);
  });
});

/** 最近使用的工作区/目录（新增工作区快捷选择） */
const recentDirs = computed(() =>
  [
    ...new Set([
      ...workspaces.value.map((workspace) => workspace.path),
      ...sessions.value.map((item) => item.work_dir),
    ]),
  ].slice(0, 6),
);

/** 目录名：取路径最后一段 */
function baseName(path: string): string {
  const parts = path.split(/[\\/]/).filter((part) => part.length > 0);
  return parts.at(-1) ?? path;
}

/** 展开/收起分组 */
function toggleGroup(key: string): void {
  collapsedGroups.value = { ...collapsedGroups.value, [key]: !collapsedGroups.value[key] };
}

/** 打开目录选择器：优先定位当前输入目录，否则列盘符 */
async function openDirPicker(): Promise<void> {
  dirDialogVisible.value = true;
  await loadDir(workspacePath.value.trim() || undefined);
}

/** 载入目录：不传 path 时列盘符 */
async function loadDir(path?: string): Promise<void> {
  const listing = await getWorkspaceDirs(path);
  dirPath.value = listing.path;
  dirParent.value = listing.parent;
  dirEntries.value = listing.entries;
}

/** 进入子目录 */
async function enterDir(path: string): Promise<void> {
  await loadDir(path);
}

/** 返回上一级 */
async function goUpDir(): Promise<void> {
  await loadDir(dirParent.value ?? undefined);
}

/** 确认选择当前目录 */
function chooseCurrentDir(): void {
  if (!dirPath.value) {
    return;
  }
  workspacePath.value = dirPath.value;
  dirDialogVisible.value = false;
}

/** 重命名会话 */
async function handleRename(item: AssistantSession): Promise<void> {
  let value = '';
  try {
    const result = await ElMessageBox.prompt('输入新的会话标题', '重命名', {
      inputValue: item.title,
      confirmButtonText: '保存',
      cancelButtonText: '取消',
    });
    value = result.value.trim();
  } catch {
    return;
  }
  if (!value) {
    return;
  }
  await updateAssistantSession(item.id, value);
  if (draftSessionId.value === item.id) {
    draftSessionId.value = null;
  }
  item.title = value;
  if (currentId.value === item.id && current.value) {
    current.value = { ...current.value, title: value };
  }
}

/** 删除工作区：其下会话一并逻辑删除，磁盘目录不动 */
async function handleDeleteWorkspace(group: SessionGroup): Promise<void> {
  const workspaceId = group.workspaceId;
  if (!workspaceId) {
    return;
  }
  try {
    await ElMessageBox.confirm(
      `确认删除工作区「${group.name}」？其下会话记录会一并删除`,
      '删除工作区',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' },
    );
  } catch {
    return;
  }
  await deleteWorkspace(workspaceId);
  const deletedIds = new Set(group.items.map((item) => item.id));
  if (draftSessionId.value && deletedIds.has(draftSessionId.value)) {
    draftSessionId.value = null;
  }
  if (current.value && deletedIds.has(current.value.id)) {
    currentId.value = '';
    current.value = null;
    messages.value = [];
    usage.value = null;
    subSessions.value = [];
  }
  await loadWorkspaces();
  await loadSessions();
  ElMessage.success('工作区已删除');
}

/** 删除单条会话：只删记录，磁盘文件不动 */
async function handleDeleteSession(item: AssistantSession): Promise<void> {
  try {
    await ElMessageBox.confirm(`删除会话「${item.title || '未命名会话'}」？`, '删除会话', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    });
  } catch {
    return;
  }
  await deleteAssistantSession(item.id);
  sessions.value = sessions.value.filter((row) => row.id !== item.id);
  if (draftSessionId.value === item.id) {
    draftSessionId.value = null;
  }
  if (currentId.value === item.id) {
    currentId.value = '';
    current.value = null;
    messages.value = [];
    usage.value = null;
    subSessions.value = [];
  }
}

/** 运行中的后台任务数（抽屉按钮角标） */
const runningSubCount = computed(
  () => subSessions.value.filter((item) => item.status === 'running').length,
);

/** 会话总 token：输入 + 输出 */
const totalTokens = computed(
  () => (usage.value?.prompt_tokens ?? 0) + (usage.value?.completion_tokens ?? 0),
);

/** 前缀缓存命中率：命中 /（命中 + 未命中）输入 token；没有数据时为 null */
const cacheRate = computed(() => {
  const current = usage.value;
  if (!current) {
    return null;
  }
  const total = current.prompt_cache_hit_tokens + current.prompt_cache_miss_tokens;
  return total > 0 ? Math.round((current.prompt_cache_hit_tokens / total) * 100) : null;
});

let abortController: AbortController | null = null;

/** 权限档位选项（对齐 DSH：能力级三档，上下排列） */
const PRESET_OPTIONS: { value: PermissionPreset; label: string }[] = [
  { value: 'read-only', label: '仅可查看' },
  { value: 'workspace-write', label: '工作区内修改' },
  { value: 'full-access', label: '完全权限' },
];

/** 当前会话的规范化档位（兼容旧值） */
const currentPreset = computed<PermissionPreset>(() => {
  const value = current.value?.permission_preset ?? 'workspace-write';
  if (value === 'loose' || value === 'standard') {
    return 'workspace-write';
  }
  if (value === 'strict') {
    return 'read-only';
  }
  return value as PermissionPreset;
});

/** 档位中文名（对齐 DSH：仅可查看 / 工作区内修改 / 完全权限；兼容旧值） */
function presetLabel(preset: string): string {
  if (preset === 'read-only' || preset === 'strict') {
    return '仅可查看';
  }
  if (preset === 'full-access') {
    return '完全权限';
  }
  return '工作区内修改';
}

/** 载入侧栏：工作区 + 会话；首次自动选中最近会话 */
async function loadSidebar(): Promise<void> {
  const [sessionList, workspaceList] = await Promise.all([getAssistantSessions(), getWorkspaces()]);
  sessions.value = sessionList;
  workspaces.value = workspaceList;
  const first = sessions.value.at(0);
  if (!currentId.value && first !== undefined) {
    await selectSession(first);
  }
}

/** 只刷新会话（回合结束、标题生成后）：无标题的草稿不进入列表 */
async function loadSessions(): Promise<void> {
  sessions.value = await visibleSessions();
}

/** 会话列表剔除草稿：无标题且不是当前会话的一律不展示 */
async function visibleSessions(): Promise<AssistantSession[]> {
  const list = await getAssistantSessions();
  return list.filter((item) => item.title.trim() !== '' || item.id === currentId.value);
}

/** 只刷新工作区 */
async function loadWorkspaces(): Promise<void> {
  workspaces.value = await getWorkspaces();
}

/** 选择会话：重放事件重建消息；先丢弃未使用的草稿会话 */
async function selectSession(item: AssistantSession): Promise<void> {
  if (draftSessionId.value && draftSessionId.value !== item.id) {
    await discardDraft();
  }
  currentId.value = item.id;
  current.value = item;
  skillsLoadedFor.value = '';
  skillCommands.value = [];
  queueItems.value = [];
  approvalPending.value = false;
  void refreshQueue();
  messages.value = [];
  const [events, calls] = await Promise.all([getSessionEvents(item.id), getSessionCalls(item.id)]);
  trajectoryEvents.value = events;
  modelCalls.value = calls;
  applyReplay(events);
  await loadUsage();
  await refreshCheckpoints();
  await loadSubSessions();
  if (subVisible.value) {
    startSubPolling();
  }
  await scrollToBottom();
}

/** 当前会话检查点 */
async function refreshCheckpoints(): Promise<void> {
  if (!currentId.value) {
    checkpoints.value = [];
    return;
  }
  checkpoints.value = await getSessionCheckpoints(currentId.value).catch(() => []);
}

/** 某回合对应的检查点 */
function checkpointForTurn(turnSeq: number | undefined): SessionCheckpoint | undefined {
  if (turnSeq === undefined) {
    return undefined;
  }
  return checkpoints.value.find((item) => item.turn_seq === turnSeq);
}

/** 从回合底部按钮执行回退 */
async function rewindTurn(view: TurnView, mode: 'both' | 'code' | 'conversation'): Promise<void> {
  const checkpoint = checkpointForTurn(view.turnSeq);
  if (checkpoint === undefined || !currentId.value) {
    return;
  }
  const result = await updateCheckpointRewind(currentId.value, checkpoint.id, mode);
  if (mode !== 'code') {
    input.value = result.prompt;
  }
  const label = mode === 'both' ? '文件 + 对话' : mode === 'code' ? '文件' : '对话';
  ElMessage.success(`已回退${label}`);
  if (current.value) {
    await selectSession(current.value);
  }
}

/** 刷新会话用量 */
async function loadUsage(): Promise<void> {
  if (!currentId.value) {
    return;
  }
  usage.value = await getSessionUsage(currentId.value);
}

/** 打开轨迹抽屉：按最新事件与调用明细刷新 */
async function openTrajectory(): Promise<void> {
  if (!currentId.value) {
    return;
  }
  [trajectoryEvents.value, modelCalls.value] = await Promise.all([
    getSessionEvents(currentId.value),
    getSessionCalls(currentId.value),
  ]);
  trajectoryVisible.value = true;
}

/** 被重试淘汰的回合 seq：重试轮会隐藏旧回合（旧答复只留在轨迹里） */
const supersededTurns = computed<Set<number>>(() => {
  const superseded = new Set<number>();
  for (const event of trajectoryEvents.value) {
    if (event.type !== 'turn/start') {
      continue;
    }
    const retryOf = event.payload['retry_of'];
    if (typeof retryOf === 'number') {
      superseded.add(retryOf);
    }
  }
  return superseded;
});

/** token 数缩写：1.2M / 760k */
function compactTokens(value: number): string {
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(1)}M`;
  if (value >= 1000) return `${String(Math.round(value / 1000))}k`;
  return String(value);
}

/** 从 assistant/message 载荷里还原工具调用行 */
function replayTools(payload: Record<string, unknown>): ToolLine[] {
  const calls = payload['tool_calls'];
  if (!Array.isArray(calls)) {
    return [];
  }
  const tools: ToolLine[] = [];
  for (const entry of calls as Array<Record<string, unknown>>) {
    tools.push({ name: String(entry['name'] ?? ''), args: String(entry['arguments'] ?? '') });
  }
  return tools;
}

/** 重放单条事件：用户消息、每个步骤的助手消息（含工具行）与工具结果回填 */
function applyReplayEvent(event: SessionEventItem, turnSeq?: number): void {
  const payload = event.payload;
  if (event.type === 'user/message') {
    const queueId = payload['queue_id'];
    if (queueId !== undefined && queueId !== null) {
      queueItems.value = queueItems.value.filter((item) => item.id !== String(queueId));
    }
    messages.value.push({
      role: 'user',
      text: String(payload['content'] ?? ''),
      thinking: '',
      tools: [],
      turnSeq,
    });
    return;
  }
  if (event.type === 'assistant/message') {
    messages.value.push({
      role: 'assistant',
      text: String(payload['content'] ?? ''),
      thinking: String(payload['reasoning_content'] ?? ''),
      tools: replayTools(payload),
      interrupted: Boolean(payload['interrupted']),
      turnSeq,
    });
    return;
  }
  if (event.type === 'tool.call') {
    const name = String(payload['name'] ?? '');
    const target = [...messages.value]
      .reverse()
      .find(
        (item) =>
          item.role === 'assistant' &&
          item.tools.some((tool) => tool.name === name && tool.startedAt === undefined),
      );
    const tool = target?.tools.find((item) => item.name === name && item.startedAt === undefined);
    if (tool !== undefined) {
      tool.startedAt = Date.parse(event.created_at);
    }
    return;
  }
  if (event.type === 'tool.result') {
    const target = [...messages.value]
      .reverse()
      .find((item) => item.role === 'assistant' && item.tools.length > 0);
    if (target !== undefined) {
      applyToolResult(target, payload, Date.parse(event.created_at));
    }
    return;
  }
  const last = messages.value.at(-1);
  if (last === undefined) {
    return;
  }
  if (event.type === 'permission.required') {
    approvalPending.value = true;
    last.permission = {
      request_id: String(payload['request_id'] ?? ''),
      name: String(payload['name'] ?? ''),
      reason: String(payload['reason'] ?? ''),
    };
  } else if (event.type === 'permission/decided') {
    approvalPending.value = false;
    last.permission = undefined;
  } else if (event.type === 'turn.paused') {
    approvalPending.value = true;
  }
}

/** 把事件重放成消息列表 */
function applyReplay(events: SessionEventItem[]): void {
  messages.value = [];
  awaitingNextStep = false;
  const rewind = [...events]
    .reverse()
    .find((event) => event.type === 'rewind' && event.payload['apply_conversation'] === true);
  const visible =
    rewind === undefined
      ? events
      : events.filter((event) => {
          const target = Number(rewind.payload['target_turn_seq'] ?? 0);
          return event.seq < target || event.seq > rewind.seq;
        });
  let replayTurnSeq: number | undefined;
  for (const event of visible) {
    if (event.type === 'turn/start') {
      replayTurnSeq = event.seq;
    }
    applyReplayEvent(event, replayTurnSeq);
  }
}

/** 按用户消息切分轮次：最后一轮运行中；完成轮把末条无工具的助手消息作为最终回复 */
const turnViews = computed<TurnView[]>(() => {
  const views: TurnView[] = [];
  for (const message of messages.value) {
    if (message.role === 'user') {
      const current = views.at(-1);
      if (
        current !== undefined &&
        message.turnSeq !== undefined &&
        current.turnSeq === message.turnSeq
      ) {
        current.userMessages.push(message);
        continue;
      }
      views.push({
        key: `turn-${String(views.length)}`,
        userMessages: [message],
        steps: [],
        answer: null,
        running: false,
        turnSeq: message.turnSeq,
      });
      continue;
    }
    const view = views.at(-1);
    if (view !== undefined) {
      view.steps.push(message);
    }
  }
  for (const [index, view] of views.entries()) {
    view.turnSeq = view.userMessages[0]?.turnSeq ?? turnStartSeqs.value[index];
    view.running = index === views.length - 1 && running.value;
    if (view.running) {
      continue;
    }
    const answer = view.steps.at(-1);
    if (answer === undefined || answer.tools.length > 0 || answer.text.trim() === '') {
      continue;
    }
    view.steps.pop();
    if (answer.thinking) {
      view.steps.push({ role: 'assistant', text: '', thinking: answer.thinking, tools: [] });
    }
    view.answer = { ...answer, thinking: '' };
  }
  return views.filter(
    (view) => view.turnSeq === undefined || !supersededTurns.value.has(view.turnSeq),
  );
});

/** 事件里每轮的 turn_seq（与用户消息顺序一致） */
const turnStartSeqs = computed<number[]>(() =>
  trajectoryEvents.value.filter((event) => event.type === 'turn/start').map((event) => event.seq),
);

/** 会话默认渲染最近若干轮，避免超长会话一次性挂载全部节点 */
const turnLimit = ref(25);

/** 当前渲染的轮次窗口 */
const pagedTurns = computed(() => turnViews.value.slice(-turnLimit.value));

/** 被窗口截掉的更早轮次数 */
const hiddenTurns = computed(() => Math.max(0, turnViews.value.length - pagedTurns.value.length));

/** 加载更早的轮次（每次 25 轮） */
function showEarlier(): void {
  turnLimit.value += 25;
}

/** 展开/收起某一轮的过程组 */ function toggleProcess(key: string): void {
  const next = new Set(expandedTurns.value);
  if (next.has(key)) {
    next.delete(key);
  } else {
    next.add(key);
  }
  expandedTurns.value = next;
}

/** 每轮耗时、token 与用量汇总：来自事件时间窗与调用明细 */
const turnMeta = computed<Partial<Record<number, TurnUsage>>>(() => {
  const windows: { seq: number; start: number; end: number }[] = [];
  const result: Partial<Record<number, TurnUsage>> = {};
  const eventUsage: Partial<
    Record<number, { prompt: number; completion: number; cacheHit: number; cacheMiss: number }>
  > = {};
  let current: { seq: number; start: number; end: number } | null = null;
  const empty = (): TurnUsage => ({
    duration: '',
    tokens: 0,
    prompt: 0,
    completion: 0,
    cacheHit: 0,
    cacheMiss: 0,
    models: new Set<string>(),
    calls: [],
  });
  for (const event of trajectoryEvents.value) {
    if (event.type === 'turn/start') {
      current = { seq: event.seq, start: Date.parse(event.created_at), end: Number.NaN };
      windows.push(current);
      result[event.seq] = empty();
      continue;
    }
    if (event.type === 'assistant/message' && current !== null) {
      const model = event.payload['model'];
      if (typeof model === 'string' && model) {
        result[current.seq]?.models.add(model);
      }
      const rawUsage = event.payload['usage'];
      if (rawUsage !== null && typeof rawUsage === 'object') {
        const usage = rawUsage as Record<string, unknown>;
        const previous = eventUsage[current.seq] ?? {
          prompt: 0,
          completion: 0,
          cacheHit: 0,
          cacheMiss: 0,
        };
        eventUsage[current.seq] = {
          prompt: previous.prompt + Number(usage['prompt_tokens'] ?? 0),
          completion: previous.completion + Number(usage['completion_tokens'] ?? 0),
          cacheHit: previous.cacheHit + Number(usage['prompt_cache_hit_tokens'] ?? 0),
          cacheMiss: previous.cacheMiss + Number(usage['prompt_cache_miss_tokens'] ?? 0),
        };
      }
    }
    if (event.type === 'turn.end' && current !== null) {
      current.end = Date.parse(event.created_at);
      const entry = result[current.seq] ?? empty();
      result[current.seq] = { ...entry, duration: durationLabel(current.start, current.end) };
      const rawUsage = event.payload['usage'];
      if (rawUsage !== null && typeof rawUsage === 'object') {
        const usage = rawUsage as Record<string, unknown>;
        eventUsage[current.seq] = {
          prompt: Number(usage['prompt_tokens'] ?? 0),
          completion: Number(usage['completion_tokens'] ?? 0),
          cacheHit: Number(usage['prompt_cache_hit_tokens'] ?? 0),
          cacheMiss: Number(usage['prompt_cache_miss_tokens'] ?? 0),
        };
      }
    }
  }
  for (const call of modelCalls.value) {
    const at = Date.parse(call.created_at);
    if (Number.isNaN(at)) {
      continue;
    }
    const window = windows.find(
      (item) => at >= item.start && (Number.isNaN(item.end) || at <= item.end),
    );
    if (window === undefined) {
      continue;
    }
    const key = window.seq;
    const entry = result[key] ?? empty();
    const prompt = call.prompt_tokens ?? 0;
    const completion = call.completion_tokens ?? 0;
    const cacheHit = call.prompt_cache_hit_tokens ?? 0;
    const cacheMiss = call.prompt_cache_miss_tokens ?? 0;
    entry.models.add(call.model);
    entry.calls.push({
      model: call.model,
      tokens: prompt + completion,
      latency: call.latency_ms === null ? '' : `${(call.latency_ms / 1000).toFixed(1)}s`,
      time: formatClock(call.created_at),
    });
    result[key] = {
      ...entry,
      tokens: entry.tokens + prompt + completion,
      prompt: entry.prompt + prompt,
      completion: entry.completion + completion,
      cacheHit: entry.cacheHit + cacheHit,
      cacheMiss: entry.cacheMiss + cacheMiss,
    };
  }
  for (const [rawSeq, usage] of Object.entries(eventUsage)) {
    const seq = Number(rawSeq);
    const entry = result[seq];
    if (entry?.calls.length === 0 && usage !== undefined) {
      result[seq] = {
        ...entry,
        tokens: usage.prompt + usage.completion,
        prompt: usage.prompt,
        completion: usage.completion,
        cacheHit: usage.cacheHit,
        cacheMiss: usage.cacheMiss,
      };
    }
  }
  return result;
});

/** 工具结果回填：按调用顺序填第一个还没有结果的工具行 */
function applyToolResult(last: ChatMessage, payload: Record<string, unknown>, at: number): void {
  const target =
    last.tools.find((tool) => tool.result === undefined && tool.error === undefined) ??
    last.tools.at(-1);
  if (target === undefined) {
    return;
  }
  target.result = String(payload['result'] ?? '');
  target.error = payload['error'] ? String(payload['error']) : undefined;
  if (target.startedAt !== undefined) {
    target.duration = durationLabel(target.startedAt, at);
  }
}

/** 应用单条实时事件：assistant/message 一步一条，带工具调用则在下一步增量时另起消息块 */
function applyEvent(type: string, payload: Record<string, unknown>): void {
  if (activeTurnSessionId.value && activeTurnSessionId.value !== currentId.value) {
    return;
  }
  if (typeof payload['seq'] === 'number') {
    trajectoryEvents.value.push({
      seq: payload['seq'],
      type,
      payload,
      created_at: String(payload['created_at'] ?? new Date().toISOString()),
    });
  }
  if (type === 'turn/start') {
    liveTurnSeq = typeof payload['seq'] === 'number' ? payload['seq'] : undefined;
    const lastUser = [...messages.value].reverse().find((item) => item.role === 'user');
    if (lastUser !== undefined && lastUser.turnSeq === undefined) {
      lastUser.turnSeq = liveTurnSeq;
    }
  }
  if (type === 'assistant/message') {
    const current = messages.value.at(-1);
    if (current?.role === 'assistant') {
      current.text = String(payload['content'] ?? '');
      const reasoning = payload['reasoning_content'];
      if (typeof reasoning === 'string') {
        current.thinking = reasoning;
      }
      const calls = payload['tool_calls'];
      awaitingNextStep = Array.isArray(calls) && calls.length > 0;
      current.interrupted = Boolean(payload['interrupted']);
    }
    return;
  }
  if (awaitingNextStep && (type === 'thinking.delta' || type === 'text.delta')) {
    messages.value.push({ role: 'assistant', text: '', thinking: '', tools: [] });
    awaitingNextStep = false;
  }
  const last = messages.value.at(-1);
  if (last === undefined) {
    return;
  }
  if (type === 'text.delta') {
    last.text += String(payload['text'] ?? '');
  } else if (type === 'thinking.delta') {
    last.thinking += String(payload['text'] ?? '');
  } else if (type === 'tool.call') {
    last.tools.push({
      name: String(payload['name'] ?? ''),
      args: String(payload['arguments'] ?? ''),
      startedAt: Date.now(),
    });
  } else if (type === 'tool.result') {
    applyToolResult(last, payload, Date.now());
  } else if (type === 'permission.required') {
    approvalPending.value = true;
    last.permission = {
      request_id: String(payload['request_id'] ?? ''),
      name: String(payload['name'] ?? ''),
      reason: String(payload['reason'] ?? ''),
    };
  } else if (type === 'permission/decided') {
    approvalPending.value = false;
    last.permission = undefined;
  } else if (type === 'error') {
    last.text += `\n[错误] ${String(payload['msg'] ?? payload['code'] ?? '')}`;
  } else if (type === 'turn.end' && String(payload['status'] ?? '') === 'pending_stop') {
    last.text += '\n（等待你的裁决）';
  } else if (type === 'turn.paused') {
    approvalPending.value = true;
  } else if (type === 'turn.end' && String(payload['status'] ?? '') === 'interrupted') {
    last.interrupted = true;
    last.text += '\n（已中断）';
  }
  void scrollToBottom();
}

/** 滚动到底部 */
async function scrollToBottom(): Promise<void> {
  await nextTick();
  const box = messageBox.value;
  if (box) {
    box.scrollTop = box.scrollHeight;
  }
}

/** 输入变化：/ 开头且未进入参数时打开命令补全 */
function onComposerInput(value: string): void {
  if (value.startsWith('/') && !value.includes(' ')) {
    slashOpen.value = true;
    slashQuery.value = value.slice(1);
    slashIndex.value = 0;
    void loadSessionSkills();
    return;
  }
  slashOpen.value = false;
}

/** 加载当前会话可用的 skill；同一会话只拉一次 */
async function loadSessionSkills(force = false): Promise<void> {
  const sessionId = currentId.value;
  if (!sessionId || (!force && skillsLoadedFor.value === sessionId)) {
    return;
  }
  const rows = await getSessionSkills(sessionId, force).catch(() => []);
  if (currentId.value !== sessionId) {
    return;
  }
  skillCommands.value = rows.map((skill: SkillItem) => ({
    name: `/${skill.name}`,
    description: skill.description || '技能',
    argsHint: skill.args_hint,
    kind: 'skill',
    skill: skill.name,
  }));
  skillsLoadedFor.value = sessionId;
}

/** 重新扫描已加载技能根目录（顶级新目录需 /reload-skills） */
async function refreshSkills(): Promise<void> {
  const sessionId = currentId.value;
  if (!sessionId || skillsLoadedFor.value !== sessionId) {
    return;
  }
  const rows = await getSessionSkills(sessionId).catch(() => []);
  if (currentId.value !== sessionId) {
    return;
  }
  skillCommands.value = rows.map((skill: SkillItem) => ({
    name: `/${skill.name}`,
    description: skill.description || '技能',
    argsHint: skill.args_hint,
    kind: 'skill',
    skill: skill.name,
  }));
}

/** 重新扫描顶级技能目录 */
async function reloadSkillsCommand(): Promise<void> {
  if (!currentId.value) {
    return;
  }
  await loadSessionSkills(true);
  appendCommandOutput(`已重新加载技能：${skillCommands.value.length} 个。`);
}

/** 关闭所有命令菜单 */
function closeComposerMenu(): void {
  slashOpen.value = false;
}

/** composer 键盘：菜单优先，其次才是发送 */
function handleComposerKeydown(raw: Event): void {
  if (!(raw instanceof KeyboardEvent)) {
    return;
  }
  const event = raw;
  if (event.isComposing) {
    return;
  }
  if (slashOpen.value) {
    const count = filteredSlashCommands.value.length;
    if (event.key === 'ArrowDown' && count > 0) {
      event.preventDefault();
      slashIndex.value = (slashIndex.value + 1) % count;
    } else if (event.key === 'ArrowUp' && count > 0) {
      event.preventDefault();
      slashIndex.value = (slashIndex.value - 1 + count) % count;
    } else if ((event.key === 'Enter' || event.key === 'Tab') && count > 0) {
      event.preventDefault();
      pickSlashCommand(filteredSlashCommands.value[slashIndex.value]);
    } else if (event.key === 'Escape') {
      event.preventDefault();
      closeComposerMenu();
    }
    return;
  }
  if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
    event.preventDefault();
    void interruptAndSend();
    return;
  }
  if (event.key === 'Escape' && running.value) {
    event.preventDefault();
    void handleStop();
    return;
  }
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault();
    handleSend();
  }
}

/** Ctrl+Enter：把当前输入排队并中断当前回合，立即开始处理 */
async function interruptAndSend(): Promise<void> {
  const content = input.value.trim();
  if (!content || !currentId.value) {
    return;
  }
  if (!running.value) {
    handleSend();
    return;
  }
  input.value = '';
  await queueCurrent(content);
  await handleStop();
}

/** 选中命令：插入技能或执行技能目录刷新 */
function pickSlashCommand(command: SlashCommand): void {
  slashOpen.value = false;
  if (command.name === '/reload-skills') {
    input.value = '';
    void reloadSkillsCommand();
    return;
  }
  if (command.kind === 'skill') {
    input.value = `${command.name} `;
  }
}

/** 发送消息：进入流式 */
function handleSend(): void {
  if (!current.value || !input.value.trim()) {
    return;
  }
  const content = input.value.trim();
  if (running.value) {
    if (content.startsWith('/')) {
      ElMessage.warning('命令需要等当前回合结束后再执行');
      return;
    }
    void queueCurrent(content);
    return;
  }
  if (content === '/reload-skills') {
    input.value = '';
    void reloadSkillsCommand();
    return;
  }
  const skillMatch = /^\/([\w.:-]+)(?:\s+([\s\S]*))?$/.exec(content);
  if (skillMatch !== null) {
    const command = skillCommands.value.find((item) => item.skill === skillMatch[1]);
    if (command !== undefined) {
      input.value = '';
      sendContent((skillMatch[2] || '').trim(), undefined, command.skill);
      return;
    }
  }
  input.value = '';
  sendContent(content);
}

/** 在消息区追加一条命令输出（不写入会话事件） */
function appendCommandOutput(text: string): void {
  messages.value.push({ role: 'assistant', text, thinking: '', tools: [] });
  void scrollToBottom();
}

/** 发送指定内容：草稿转正、推入用户与助手占位、开启 SSE；skill 指定技能名 */
function sendContent(content: string, retryOf?: number, skill?: string, queueId?: string): void {
  if (!current.value || running.value) {
    return;
  }
  // 发出首条消息即转正：不再按草稿丢弃
  draftSessionId.value = null;
  awaitingNextStep = false;
  messages.value.push({
    role: 'user',
    text: content || (skill ? `/${skill}` : ''),
    thinking: '',
    tools: [],
  });
  messages.value.push({ role: 'assistant', text: '', thinking: '', tools: [] });
  const turnSessionId = currentId.value;
  runningSessionId.value = turnSessionId;
  stopping.value = false;
  activeTurnSessionId.value = turnSessionId;
  abortController = addTurnStream(
    turnSessionId,
    content,
    {
      onEvent: applyEvent,
      onDone: () => {
        finishTurn();
        if (currentId.value !== turnSessionId) {
          void refreshSessions();
          return;
        }
        void loadUsage();
        void Promise.all([getSessionEvents(turnSessionId), getSessionCalls(turnSessionId)]).then(
          ([events, calls]) => {
            trajectoryEvents.value = events;
            modelCalls.value = calls;
          },
        );
        void refreshSessions();
        void refreshCheckpoints();
        void drainQueue();
      },
      onError: (message) => {
        finishTurn();
        ElMessage.error(message);
      },
    },
    retryOf,
    skill,
    queueId,
  );
}

/** 刷新当前会话排队消息 */
async function refreshQueue(): Promise<void> {
  const sessionId = currentId.value;
  if (!sessionId) {
    return;
  }
  const rows = await getSessionQueue(sessionId).catch(() => []);
  if (currentId.value === sessionId) {
    queueItems.value = rows;
  }
}

/** 运行中发送：进入队列，不打断当前回合 */
async function queueCurrent(content: string): Promise<void> {
  if (!currentId.value) {
    return;
  }
  const created = await addSessionQueue(currentId.value, content);
  queueItems.value = [
    ...queueItems.value,
    { id: created.id, content, created_at: new Date().toISOString() },
  ];
  input.value = '';
}

/** 回合结束后自动发送队列中的下一条 */
async function drainQueue(): Promise<void> {
  if (running.value || approvalPending.value || !currentId.value) {
    return;
  }
  const next = queueItems.value.at(0);
  if (next === undefined) {
    return;
  }
  queueItems.value = queueItems.value.filter((item) => item.id !== next.id);
  sendContent(next.content, undefined, undefined, next.id);
}

/** 编辑排队消息 */
async function editQueueItem(item: QueueItem): Promise<void> {
  const result = await ElMessageBox.prompt('修改排队消息', '编辑排队消息', {
    inputValue: item.content,
    confirmButtonText: '保存',
    cancelButtonText: '取消',
  }).catch(() => null);
  if (result === null || !currentId.value || !result.value.trim()) {
    return;
  }
  await updateSessionQueue(currentId.value, item.id, result.value.trim());
  await refreshQueue();
}

/** 撤回排队消息 */
async function removeQueueItem(item: QueueItem): Promise<void> {
  if (!currentId.value) {
    return;
  }
  const result = await deleteSessionQueue(currentId.value, item.id);
  if (!result.removed) {
    ElMessage.warning(
      result.reason === 'already_injected'
        ? '这条消息已经开始处理，无法撤回'
        : '消息已不存在，正在刷新队列',
    );
    await refreshQueue();
    return;
  }
  queueItems.value = queueItems.value.filter((row) => row.id !== item.id);
}

/** 开始拖动排队消息 */
function onQueueDragStart(index: number): void {
  dragIndex.value = index;
  dragOverIndex.value = index;
}

/** 拖动经过目标位置 */
function onQueueDragOver(index: number): void {
  if (dragIndex.value !== -1) {
    dragOverIndex.value = index;
  }
}

/** 放下后先本地重排，再持久化顺序 */
async function onQueueDrop(): Promise<void> {
  const from = dragIndex.value;
  const to = dragOverIndex.value;
  dragIndex.value = -1;
  dragOverIndex.value = -1;
  if (from < 0 || to < 0 || from === to) {
    return;
  }
  const next = [...queueItems.value];
  const [moved] = next.splice(from, 1);
  next.splice(to, 0, moved);
  queueItems.value = next;
  if (!currentId.value) {
    return;
  }
  const saved = await updateSessionQueueOrder(
    currentId.value,
    next.map((item) => item.id),
  ).catch(() => false);
  if (!saved) {
    await refreshQueue();
  }
}

/** 结束拖动，清理高亮 */
function onQueueDragEnd(): void {
  dragIndex.value = -1;
  dragOverIndex.value = -1;
}

/** 回合收尾：清理运行态、停止计时与目标会话 */
function finishTurn(): void {
  runningSessionId.value = '';
  stopping.value = false;
  activeTurnSessionId.value = '';
  if (stopTimer !== undefined) {
    window.clearTimeout(stopTimer);
    stopTimer = undefined;
  }
}

/** 停止：先请求服务端中断，保持 SSE 等 turn.end；超时才断开 */
async function handleStop(): Promise<void> {
  const target = activeTurnSessionId.value || currentId.value;
  if (!target || stopping.value) {
    return;
  }
  stopping.value = true;
  let result: { accepted: boolean };
  try {
    result = await updateTurnStop(target);
  } catch {
    abortController?.abort();
    abortController = null;
    finishTurn();
    return;
  }
  if (!result.accepted) {
    abortController?.abort();
    abortController = null;
    finishTurn();
    return;
  }
  stopTimer = window.setTimeout(() => {
    abortController?.abort();
    abortController = null;
    finishTurn();
  }, 5000);
}

/** 提交后台任务：当前输入内容交给子会话执行（干净上下文） */
async function handleBackground(): Promise<void> {
  if (!current.value || !input.value.trim() || running.value) {
    return;
  }
  const task = input.value.trim();
  input.value = '';
  await addSubSession(currentId.value, task);
  ElMessage.success('已转后台执行，结果可在「后台任务」查看');
  await loadSubSessions();
  subVisible.value = true;
  startSubPolling();
}

/** 打开后台任务抽屉：拉列表并开始轮询 */
async function openSubSessions(): Promise<void> {
  await loadSubSessions();
  subVisible.value = true;
  startSubPolling();
}

/** 载入后台任务列表 */
async function loadSubSessions(): Promise<void> {
  if (!currentId.value) {
    return;
  }
  subSessions.value = await getSubSessions(currentId.value);
}

/** 轮询：抽屉打开且有运行中任务时每 3 秒刷新 */
function startSubPolling(): void {
  stopSubPolling();
  subTimer = window.setInterval(() => {
    if (!subVisible.value) {
      stopSubPolling();
      return;
    }
    void loadSubSessions();
    if (runningSubCount.value === 0) {
      stopSubPolling();
    }
  }, 3000);
}

/** 停止轮询 */
function stopSubPolling(): void {
  if (subTimer !== null) {
    window.clearInterval(subTimer);
    subTimer = null;
  }
}

/** 裁决后恢复重放 */
async function decidePermission(
  item: ChatMessage,
  outcome: 'allowed-once' | 'rejected',
): Promise<void> {
  const permission = item.permission;
  if (!permission) {
    return;
  }
  item.permission = undefined;
  approvalPending.value = false;
  try {
    const accepted = await addTurnResume(currentId.value, permission.request_id, outcome);
    if (!accepted) {
      ElMessage.warning('审批已失效，请刷新会话');
      if (current.value) {
        await selectSession(current.value);
      }
    }
  } catch {
    item.permission = permission;
    approvalPending.value = true;
    ElMessage.error('审批提交失败，请重试');
  }
}

/** 切换本会话权限档：完全权限需二次确认（对齐 DSH 的风险确认） */
async function changePreset(value: string | number | boolean | undefined): Promise<void> {
  if (!current.value || typeof value !== 'string') {
    return;
  }
  if (value === 'full-access') {
    try {
      await ElMessageBox.confirm(
        '启用完全权限后，智能体将减少确认步骤，并且可以直接执行更多操作，包括敏感操作、文件修改或外部命令。仅建议在你信任当前任务时使用。',
        '开启完全权限',
        { type: 'warning', confirmButtonText: '确认开启', cancelButtonText: '取消' },
      );
    } catch {
      return;
    }
  }
  await updateSessionPermission(currentId.value, value as PermissionPreset);
  current.value = { ...current.value, permission_preset: value };
}

/** 工作区行菜单：复制路径 / 重命名 / 删除会话记录 */
async function handleGroupCommand(command: unknown, group: SessionGroup): Promise<void> {
  const action = String(command);
  if (action === 'copy') {
    try {
      await navigator.clipboard.writeText(group.path);
      ElMessage.success('路径已复制');
    } catch {
      ElMessage.error('复制失败，请手动选择路径');
    }
    return;
  }
  if (action === 'rename') {
    await handleRenameGroup(group);
    return;
  }
  if (action === 'delete-workspace') {
    await handleDeleteWorkspace(group);
  }
}

/** 重命名工作区：只改显示名，不动目录与会话归属 */
async function handleRenameGroup(group: SessionGroup): Promise<void> {
  if (!group.workspaceId) {
    return;
  }
  let value = '';
  try {
    const result = await ElMessageBox.prompt('输入新的工作区名称', '重命名工作区', {
      inputValue: group.name,
      confirmButtonText: '保存',
      cancelButtonText: '取消',
    });
    value = result.value.trim();
  } catch {
    return;
  }
  if (!value || value === group.name) {
    return;
  }
  await updateWorkspace(group.workspaceId, value);
  await loadWorkspaces();
  ElMessage.success('工作区已重命名');
}

/** 打开新增工作区弹窗 */
function openWorkspaceDialog(): void {
  workspacePath.value = '';
  workspaceDialogVisible.value = true;
}

/** 新增工作区：目录选一次，之后会话都归属它；名称默认取目录名 */
async function handleCreateWorkspace(): Promise<void> {
  if (!workspacePath.value.trim()) {
    return;
  }
  const workspace = await addWorkspace(workspacePath.value.trim());
  workspaceDialogVisible.value = false;
  workspacePath.value = '';
  await loadWorkspaces();
  // 对齐 DSH：新建工作区后直接打开一个可聊的草稿会话
  await openDraftSession(workspace.id, workspace.path);
  ElMessage.success('工作区已创建，已打开新会话');
}

/** 在工作区下新建会话：不再选目录，直接继承工作区 */
async function handleNewSession(group: SessionGroup): Promise<void> {
  const workspaceId = group.workspaceId;
  if (!workspaceId) {
    return;
  }
  await openDraftSession(workspaceId, group.path);
}

/** 打开草稿会话：未发消息就切走会被丢弃（对齐 DSH） */
async function openDraftSession(workspaceId: string, path: string): Promise<void> {
  await discardDraft();
  const created = await addAssistantSession(path, '', workspaceId);
  sessions.value.unshift(created);
  draftSessionId.value = created.id;
  await selectSession(created);
}

/** 丢弃未使用的草稿会话：从列表移除并逻辑删除；清理失败不影响切换 */
async function discardDraft(): Promise<void> {
  const id = draftSessionId.value;
  draftSessionId.value = null;
  if (!id) {
    return;
  }
  sessions.value = sessions.value.filter((item) => item.id !== id);
  if (currentId.value === id) {
    currentId.value = '';
    current.value = null;
    messages.value = [];
    usage.value = null;
    subSessions.value = [];
  }
  try {
    await deleteAssistantSession(id);
  } catch {
    // 草稿可能已被别处删除：忽略清理失败，切换照常进行
  }
}

/** 刷新会话列表：首条消息后标题由后端自动生成，同步到列表与当前会话 */
async function refreshSessions(): Promise<void> {
  sessions.value = await visibleSessions();
  if (currentId.value) {
    const latest = sessions.value.find((item) => item.id === currentId.value);
    if (latest) {
      current.value = latest;
    }
  }
}

onMounted(async () => {
  await loadSidebar();
  chatModels.value = await getAvailableModels('CHAT').catch(() => []);
  skillTimer = window.setInterval(() => {
    void refreshSkills();
  }, 2000);
  if (currentId.value) {
    await loadSubSessions();
  }
});

onUnmounted(() => {
  stopSubPolling();
  if (skillTimer !== undefined) {
    window.clearInterval(skillTimer);
    skillTimer = undefined;
  }
});
</script>

<style scoped lang="css">
.assistant-page {
  gap: 14px;
}

.chat-wrap {
  position: relative;
  display: grid;
  flex: 1;
  min-height: 0;
  overflow: hidden;
  grid-template-columns: 240px minmax(0, 1fr);
  gap: 0;
}

.chat-panel {
  position: relative;
  display: flex;
  min-width: 0;
  min-height: 0;
  flex-direction: column;
  overflow: hidden;
  background:
    radial-gradient(720px 260px at 85% -8%, var(--kb-tint), transparent 72%), var(--kb-bg-2);
  transition: opacity 0.8s ease;
}

/*
 * 切换会话栏宽度时，先把两侧淡出，再瞬间改布局，最后淡入。
 * 宽度是布局属性，若直接做过渡，正文会逐帧重排导致行数跳动；
 * 在不可见时完成重排，用户只看到一次干净的淡入淡出与网格崩解。
 */
.chat-wrap.switching .chat-panel {
  opacity: 0;
}

.chat-wrap.sessions-collapsed {
  grid-template-columns: 0 minmax(0, 1fr);
}

/* 亮网格扫描层：整片 16px 网格，靠 clip-path 从左到右被擦掉 */
.grid-wipe {
  position: absolute;
  inset: 0;
  z-index: 6;
  overflow: hidden;
  background-image:
    linear-gradient(rgb(34 224 255 / 30%) 1px, transparent 1px),
    linear-gradient(90deg, rgb(34 224 255 / 30%) 1px, transparent 1px);
  background-size: 16px 16px;
  pointer-events: none;
  will-change: clip-path;
}

.wipe-edge {
  position: absolute;
  top: 0;
  bottom: 0;
  width: 2px;
  background: var(--kb-primary);
  box-shadow:
    0 0 12px var(--kb-primary),
    0 0 28px rgb(34 224 255 / 55%);
}

@media (prefers-reduced-motion: reduce) {
  .chat-panel {
    transition: none;
  }

  .grid-wipe {
    display: none;
  }
}

/* HUD 角标：左上 / 右下 */
.chat-panel::before,
.chat-panel::after {
  position: absolute;
  z-index: 3;
  width: 12px;
  height: 12px;
  border-style: solid;
  border-color: var(--kb-primary);
  border-width: 0;
  filter: drop-shadow(0 0 4px var(--kb-glow));
  opacity: 0.55;
  pointer-events: none;
  content: '';
}

.chat-panel::before {
  top: 8px;
  left: 8px;
  border-top-width: 1px;
  border-left-width: 1px;
}

.chat-panel::after {
  right: 8px;
  bottom: 8px;
  border-right-width: 1px;
  border-bottom-width: 1px;
}

.composer-model {
  width: 210px;
  flex: none;
}

.messages {
  display: flex;
  flex: 1;
  min-height: 0;
  flex-direction: column;
  gap: 24px;
  overflow: auto;
  padding: 24px 28px 28px;
}

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
</style>
