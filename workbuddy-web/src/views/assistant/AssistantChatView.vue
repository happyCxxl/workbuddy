<template>
  <div class="page assistant-page">
    <div class="page-head">
      <div>
        <h1 class="page-title">对话</h1>
        <p class="page-desc">
          先新增工作区，再在工作区里新建会话；可乐会读写工作区目录、跑命令、查资料，高危操作先问过你。
        </p>
      </div>
    </div>

    <div class="chat-wrap">
      <!-- 工作区列表 -->
      <div class="sessions-panel">
        <div class="sessions-head">
          <span class="sessions-title">工作区</span>
          <span class="page-spacer"></span>
          <button
            class="head-icon"
            :class="{ 'head-icon-on': searchVisible }"
            type="button"
            title="搜索工作区"
            @click="searchVisible = !searchVisible"
          >
            <svg
              width="13"
              height="13"
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
            :class="{ 'head-icon-on': onlyWithSessions }"
            type="button"
            title="仅显示有会话的工作区"
            @click="onlyWithSessions = !onlyWithSessions"
          >
            <svg
              width="13"
              height="13"
              viewBox="0 0 16 16"
              fill="none"
              stroke="currentColor"
              stroke-width="1.5"
            >
              <path d="M2.5 4h11M4.5 8h7M6.5 12h3" />
            </svg>
          </button>
          <button class="head-icon" type="button" title="新增工作区" @click="openWorkspaceDialog">
            <svg
              width="13"
              height="13"
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
        <div v-if="searchVisible" class="workspace-search">
          <el-input
            v-model="workspaceKeyword"
            size="small"
            placeholder="搜索工作区名称或路径"
            clearable
          />
        </div>
        <div class="sessions-list">
          <div v-for="group in sessionGroups" :key="group.key" class="session-group">
            <div
              class="group-head"
              :class="{ 'group-head-on': isGroupActive(group) }"
              @click="toggleGroup(group.key)"
            >
              <span class="group-folder">
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
              <span class="group-arrow" :class="{ 'group-arrow-open': !collapsedGroups[group.key] }"
                >▸</span
              >
              <span class="group-name" :title="group.path">{{ group.name }}</span>
              <span v-if="group.workspaceId" class="group-actions" @click.stop>
                <el-dropdown trigger="click" @command="handleGroupCommand($event, group)">
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
                  @click.stop="handleNewSession(group)"
                >
                  ＋
                </button>
              </span>
            </div>
            <div v-show="!collapsedGroups[group.key]" class="group-items">
              <p v-if="group.items.length === 0" class="group-empty">暂无会话</p>
              <button
                v-for="item in group.items"
                :key="item.id"
                type="button"
                class="session-item"
                :class="{ 'session-item-on': item.id === currentId }"
                @click="selectSession(item)"
              >
                <span class="session-title">{{ item.title || '未命名会话' }}</span>
                <span class="session-time">{{ formatTime(item.updated_at) }}</span>
                <span class="session-rename" title="重命名" @click.stop="handleRename(item)"
                  >✎</span
                >
                <span
                  class="session-delete"
                  title="删除会话"
                  @click.stop="handleDeleteSession(item)"
                  >✕</span
                >
              </button>
            </div>
          </div>
          <p v-if="workspaces.length === 0 && sessions.length === 0" class="session-empty">
            暂无工作区 暂无工作区
          </p>
        </div>
      </div>

      <!-- 对话区 -->
      <div class="chat-panel">
        <div class="chat-head">
          <span class="chat-title">{{ current?.title || '选择或新建一个会话' }}</span>
          <span v-if="current" class="chat-dir">{{ current.work_dir }}</span>
          <span class="page-spacer"></span>
          <span v-if="usage" class="chat-usage">
            {{ usage.calls }} 次调用 · {{ totalTokens }} tokens
            <template v-if="cacheRate !== null"> · 缓存 {{ cacheRate }}%</template>
          </span>
          <el-button v-if="current" size="small" text @click="openTrajectory">轨迹</el-button>
          <el-button v-if="current" size="small" text @click="openSubSessions">
            后台任务
            <span v-if="runningSubCount > 0" class="sub-count">{{ runningSubCount }}</span>
          </el-button>
          <span v-if="running" class="chat-status">执行中 · 第 {{ stepLabel }} 步</span>
        </div>

        <div ref="messageBox" class="messages">
          <p v-if="messages.length === 0" class="messages-empty">
            发一句话开始干活。可乐会按人格版本 {{ personaLabel }} 说话，按权限档执行工具。
          </p>
          <section v-for="view in turnViews" :key="view.key" class="turn">
            <div v-if="view.user" class="message message-user">
              <div class="message-bubble">{{ view.user.text }}</div>
            </div>
            <div v-if="view.steps.length > 0" class="process">
              <button class="process-head" type="button" @click="toggleProcess(view.key)">
                <span class="process-title">{{ view.running ? '执行中' : '工作步骤' }}</span>
                <span class="process-meta">{{ view.steps.length }} 步</span>
                <span class="process-arrow">{{
                  view.running || expandedTurns.has(view.key) ? '收起' : '展开'
                }}</span>
              </button>
              <div v-show="view.running || expandedTurns.has(view.key)" class="process-body">
                <div v-for="(step, stepIndex) in view.steps" :key="stepIndex" class="step">
                  <details v-if="step.thinking" class="message-thinking">
                    <summary class="thinking-summary">思考过程</summary>
                    <pre class="thinking-body">{{ step.thinking }}</pre>
                  </details>
                  <MarkdownView v-if="step.text" :content="step.text" />
                  <details
                    v-for="(tool, toolIndex) in step.tools"
                    :key="toolIndex"
                    class="tool-card"
                    :class="{ 'tool-fail': Boolean(tool.error) }"
                  >
                    <summary class="tool-head">
                      <span
                        class="tool-dot"
                        :class="tool.error ? 'is-fail' : tool.result ? 'is-ok' : 'is-run'"
                      ></span>
                      <span class="tool-name">{{ tool.name }}</span>
                      <span class="tool-args">{{ tool.args }}</span>
                      <span class="tool-state">{{
                        tool.error ? '失败' : tool.result ? '完成' : '执行中'
                      }}</span>
                    </summary>
                    <div class="tool-body">
                      <p class="tool-label">参数</p>
                      <pre class="tool-pre">{{ prettyJson(tool.args) }}</pre>
                      <template v-if="tool.error">
                        <p class="tool-label">错误</p>
                        <pre class="tool-pre tool-error">{{ tool.error }}</pre>
                      </template>
                      <template v-else-if="tool.result">
                        <p class="tool-label">结果</p>
                        <pre class="tool-pre">{{ tool.result }}</pre>
                      </template>
                    </div>
                  </details>
                  <div v-if="step.permission" class="message-perm">
                    <div class="perm-title">⚠ 需要你确认：{{ step.permission.name }}</div>
                    <div class="perm-reason">{{ step.permission.reason }}</div>
                    <div class="perm-actions">
                      <el-button
                        size="small"
                        type="primary"
                        @click="decidePermission(step, 'allowed-once')"
                      >
                        允许一次
                      </el-button>
                      <el-button size="small" @click="decidePermission(step, 'allowed-always')">
                        始终允许（本目录）
                      </el-button>
                      <el-button
                        size="small"
                        type="danger"
                        plain
                        @click="decidePermission(step, 'rejected')"
                      >
                        拒绝
                      </el-button>
                    </div>
                  </div>
                </div>
              </div>
            </div>
            <div v-if="view.answer" class="message message-assistant message-answer">
              <MarkdownView :content="view.answer.text" />
            </div>
          </section>
        </div>

        <div class="composer">
          <el-input
            v-model="input"
            type="textarea"
            :rows="2"
            resize="none"
            placeholder="继续输入… Enter 发送 · Shift+Enter 换行"
            :disabled="!current"
            @keydown.enter.exact.prevent="handleSend"
          />
          <div class="composer-row">
            <div class="composer-hints">
              <span>记忆注入：自动</span>
              <el-popover placement="top" width="300" trigger="click">
                <template #reference>
                  <button class="hint-link" type="button" :disabled="!current">
                    权限 {{ presetLabel(current?.permission_preset ?? 'standard') }} ▾
                  </button>
                </template>
                <div class="preset-box">
                  <p class="preset-title">本会话权限档位</p>
                  <el-radio-group
                    :model-value="current?.permission_preset"
                    size="small"
                    @change="changePreset"
                  >
                    <el-radio-button value="loose">宽松档</el-radio-button>
                    <el-radio-button value="standard">标准档</el-radio-button>
                    <el-radio-button value="strict">严格档</el-radio-button>
                  </el-radio-group>
                  <p class="preset-note">只影响本会话；高危操作始终询问。</p>
                </div>
              </el-popover>
            </div>
            <el-button v-if="running" type="danger" plain @click="handleStop">■ 停止</el-button>
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

    <el-dialog v-model="workspaceDialogVisible" title="新增工作区" width="520px" destroy-on-close>
      <el-form label-position="top">
        <el-form-item label="工作区目录（会话的文件操作限定在此目录）">
          <div class="workdir-row">
            <el-input v-model="workspacePath" placeholder="请选择目录，或直接输入绝对路径" />
            <el-button @click="openDirPicker">选择目录</el-button>
          </div>
          <div v-if="recentDirs.length > 0" class="recent-dirs">
            <span class="recent-label">最近：</span>
            <button
              v-for="dir in recentDirs"
              :key="dir"
              type="button"
              class="dir-chip"
              :class="{ 'dir-chip-on': workspacePath === dir }"
              :title="dir"
              @click="workspacePath = dir"
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
        <el-button @click="workspaceDialogVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!workspacePath.trim()" @click="handleCreateWorkspace">
          创建工作区
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dirDialogVisible" title="选择工作目录" width="560px">
      <div class="dir-bar">
        <el-button size="small" :disabled="dirParent === null" @click="goUpDir">上一级</el-button>
        <span class="dir-path" :title="dirPath || '此电脑'">{{ dirPath || '此电脑' }}</span>
      </div>
      <div class="dir-list">
        <p v-if="dirEntries.length === 0" class="dir-empty">没有子目录。</p>
        <button
          v-for="entry in dirEntries"
          :key="entry.path"
          type="button"
          class="dir-item"
          @click="enterDir(entry.path)"
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
        <el-button @click="dirDialogVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!dirPath" @click="chooseCurrentDir">
          选择此目录
        </el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="trajectoryVisible" title="活动时间线" size="52%">
      <div class="trail">
        <p v-if="trailTurns.length === 0" class="trajectory-empty">还没有活动。</p>
        <section v-for="turn in trailTurns" :key="turn.key" class="trail-turn">
          <header class="trail-head">
            <span class="trail-index">第 {{ turn.index }} 轮</span>
            <span class="trail-time">{{ turn.startedAt }}</span>
            <span class="trail-status" :data-status="turn.status">{{ turn.statusLabel }}</span>
            <span class="trail-duration">{{ turn.duration }}</span>
          </header>
          <div v-for="node in turn.nodes" :key="node.key" class="trail-node" :data-kind="node.kind">
            <div class="trail-line">
              <span class="trail-badge">{{ node.badge }}</span>
              <span class="trail-title">{{ node.title }}</span>
              <span v-if="node.duration" class="trail-node-duration">{{ node.duration }}</span>
              <span class="trail-node-time">{{ node.time }}</span>
            </div>
            <details v-if="node.detail" class="trail-detail">
              <summary class="trail-summary">详情</summary>
              <pre class="trail-pre">{{ node.detail }}</pre>
            </details>
          </div>
        </section>
      </div>
    </el-drawer>

    <el-drawer v-model="subVisible" title="后台任务" size="46%">
      <div class="subs">
        <p class="subs-hint">
          子会话只完成给定任务：干净上下文（无人格、无记忆），权限沿用本会话，审批自动拒绝。
        </p>
        <p v-if="subSessions.length === 0" class="trajectory-empty">还没有后台任务。</p>
        <div v-for="item in subSessions" :key="item.id" class="sub-item">
          <div class="sub-head">
            <span class="sub-title">{{ item.title || '未命名任务' }}</span>
            <el-tag :type="subTagType(item.status)" effect="plain" size="small">
              {{ subStatusLabel(item.status) }}
            </el-tag>
            <span class="sub-time">{{ formatTime(item.created_at) }}</span>
          </div>
          <p class="sub-task">{{ item.task }}</p>
          <pre v-if="item.result" class="sub-result">{{ item.result }}</pre>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus';
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue';

import {
  addAssistantSession,
  addSubSession,
  addTurnResume,
  addTurnStream,
  addWorkspace,
  deleteAssistantSession,
  deleteWorkspace,
  getAssistantSessions,
  getSessionCalls,
  getSessionEvents,
  getSessionUsage,
  getSubSessions,
  getWorkspaceDirs,
  getWorkspaces,
  updateAssistantSession,
  updateSessionPermission,
  updateTurnStop,
  updateWorkspace,
} from '@/api/assistant';
import type {
  AssistantSession,
  DirEntry,
  ModelCallItem,
  PermissionPreset,
  SessionEventItem,
  SessionUsage,
  SubSessionItem,
  Workspace,
} from '@/types/assistant';
import { formatDateTime } from '@/utils/date';
import MarkdownView from '@/components/assistant/MarkdownView.vue';

/** 会话分组：一个工作区一组（无工作区的旧会话按目录兜底成组） */
interface SessionGroup {
  key: string;
  name: string;
  path: string;
  workspaceId: string | null;
  items: AssistantSession[];
}

/** 工具调用行：名称、参数与结果 */
interface ToolLine {
  name: string;
  args: string;
  result?: string;
  error?: string;
}

/** 待审批信息：request_id 用于恢复重放 */
interface PermissionInfo {
  request_id: string;
  name: string;
  reason: string;
}

/** 消息：用户或可乐；可乐可带思考、工具与审批卡 */
interface ChatMessage {
  role: 'user' | 'assistant';
  text: string;
  thinking: string;
  tools: ToolLine[];
  permission?: PermissionInfo;
}

const sessions = ref<AssistantSession[]>([]);
const currentId = ref<string>('');
const current = ref<AssistantSession | null>(null);
/** 草稿会话：新建工作区/点「＋」时自动创建，未发消息就切走即丢弃（对齐 DSH） */
const draftSessionId = ref<string | null>(null);
const messages = ref<ChatMessage[]>([]);
/** 上一步以工具调用收尾：下一个增量事件开启新的步骤块，保证"思考 → 工具 → 思考"的循环可见 */
let awaitingNextStep = false;
const input = ref('');
const running = ref(false);
/** 过程组展开状态：完成的轮次默认折叠，运行中的轮次强制展开 */
const expandedTurns = ref<Set<string>>(new Set());
const stepLabel = ref('1');
const personaLabel = ref('v1');
const messageBox = ref<HTMLElement | null>(null);
const workspaceDialogVisible = ref(false);
const workspacePath = ref('');
const workspaces = ref<Workspace[]>([]);
const searchVisible = ref(false);
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

/** 档位中文名 */
function presetLabel(preset: string): string {
  if (preset === 'loose') {
    return '宽松档';
  }
  if (preset === 'strict') {
    return '严格档';
  }
  return '标准档';
}

/** 时间格式化：统一走工具函数 */
function formatTime(value: string): string {
  return formatDateTime(value);
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
  messages.value = [];
  const events = await getSessionEvents(item.id);
  trajectoryEvents.value = events;
  applyReplay(events);
  await loadUsage();
  await loadSubSessions();
  if (subVisible.value) {
    startSubPolling();
  }
  await scrollToBottom();
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

// 活动时间线：按轮次分组，只保留语义节点（文本/思考增量不单独成条）
interface TrailNode {
  key: string;
  kind: 'user' | 'assistant' | 'tool' | 'call' | 'error';
  badge: string;
  title: string;
  detail?: string;
  time: string;
  /** 工具/步骤耗时（开始到结果的时间差） */
  duration?: string;
  startedMs?: number;
}
interface TrailTurn {
  key: string;
  index: number;
  startedAt: string;
  startedMs: number;
  endedMs: number | null;
  status: string;
  statusLabel: string;
  duration: string;
  nodes: TrailNode[];
}

/** 新轮次：turn/start 建一个分组 */
function newTrailTurn(index: number, event: SessionEventItem): TrailTurn {
  const at = Date.parse(event.created_at);
  return {
    key: `turn-${String(event.seq)}`,
    index,
    startedAt: formatClock(event.created_at),
    startedMs: Number.isNaN(at) ? 0 : at,
    endedMs: null,
    status: 'running',
    statusLabel: '进行中',
    duration: '',
    nodes: [],
  };
}

/** 单事件归并为节点：文本/思考增量直接跳过 */
function appendTrailEvent(turn: TrailTurn, event: SessionEventItem): void {
  const time = formatClock(event.created_at);
  const at = Date.parse(event.created_at);
  if (event.type === 'user/message') {
    const content = String(event.payload['content'] ?? '');
    turn.nodes.push({
      key: `e${String(event.seq)}`,
      kind: 'user',
      badge: '你',
      title: preview(content),
      detail: content,
      time,
    });
    return;
  }
  if (event.type === 'assistant/message') {
    const content = String(event.payload['content'] ?? '').trim();
    if (!content) {
      return;
    }
    turn.nodes.push({
      key: `a${String(event.seq)}`,
      kind: 'assistant',
      badge: '助手',
      title: preview(content),
      detail: content,
      time,
    });
    return;
  }
  if (event.type === 'tool.call') {
    const name = String(event.payload['name'] ?? '');
    const args = String(event.payload['arguments'] ?? '');
    turn.nodes.push({
      key: `t${String(event.seq)}`,
      kind: 'tool',
      badge: '工具',
      title: `${name} ${preview(args, 80)}`,
      detail: prettyJson(args),
      time,
      startedMs: at,
    });
    return;
  }
  if (event.type === 'tool.result') {
    const name = String(event.payload['name'] ?? '');
    const node = [...turn.nodes]
      .reverse()
      .find((item) => item.kind === 'tool' && item.title.startsWith(name));
    if (!node) {
      return;
    }
    const error = event.payload['error'];
    node.duration = durationLabel(node.startedMs ?? at, at);
    if (error) {
      node.kind = 'error';
      node.badge = '失败';
      node.title = `${name} 失败：${preview(String(error), 100)}`;
      node.detail = String(error);
      return;
    }
    node.title = `${node.title} → ${preview(String(event.payload['result'] ?? ''), 80)}`;
    return;
  }
  if (event.type === 'error') {
    const message = String(event.payload['message'] ?? '');
    turn.nodes.push({
      key: `x${String(event.seq)}`,
      kind: 'error',
      badge: '错误',
      title: preview(message, 120),
      detail: message,
      time,
    });
    return;
  }
  if (event.type === 'turn.end') {
    turn.status = String(event.payload['status'] ?? 'completed');
    turn.statusLabel = trailStatusLabel(turn.status);
    const at = Date.parse(event.created_at);
    turn.endedMs = Number.isNaN(at) ? null : at;
    turn.duration = durationLabel(turn.startedMs, turn.endedMs);
  }
}

/** 模型调用按发生时间归入对应轮次（调用明细没有 turn_seq） */
function attachModelCalls(turns: TrailTurn[], calls: ModelCallItem[]): void {
  for (const call of calls) {
    const at = Date.parse(call.created_at);
    if (Number.isNaN(at)) {
      continue;
    }
    const turn = turns.find(
      (item) => at >= item.startedMs && (item.endedMs === null || at <= item.endedMs),
    );
    if (!turn) {
      continue;
    }
    const latency = call.latency_ms === null ? '' : ` · ${(call.latency_ms / 1000).toFixed(1)}s`;
    const tokens =
      call.prompt_tokens === null
        ? ''
        : ` · ${String(call.prompt_tokens)}+${String(call.completion_tokens ?? 0)} tok`;
    const failed = call.error_code === null ? '' : ` · 错误 ${String(call.error_code)}`;
    turn.nodes.push({
      key: `call-${call.created_at}-${call.model}`,
      kind: 'call',
      badge: '模型',
      title: `${call.model}${latency}${tokens}${failed}`,
      time: formatClock(call.created_at),
    });
  }
}

/** 活动时间线：事件 + 调用明细归并成"轮次 → 节点" */
const trailTurns = computed<TrailTurn[]>(() => {
  const turns: TrailTurn[] = [];
  let current: TrailTurn | null = null;
  for (const event of trajectoryEvents.value) {
    if (event.type === 'turn/start') {
      current = newTrailTurn(turns.length + 1, event);
      turns.push(current);
      continue;
    }
    if (current !== null) {
      appendTrailEvent(current, event);
    }
  }
  attachModelCalls(turns, modelCalls.value);
  return turns;
});

/** 单行摘要：折叠空白并截断 */
function preview(text: string, limit = 120): string {
  const flat = text.replace(/\s+/g, ' ').trim();
  return flat.length > limit ? `${flat.slice(0, limit)}…` : flat;
}

/** 参数格式化：JSON 可解析则缩进，否则原样 */
function prettyJson(text: string): string {
  try {
    return JSON.stringify(JSON.parse(text), null, 2);
  } catch {
    return text;
  }
}

/** 时间显示：HH:mm:ss */
function formatClock(iso: string): string {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleTimeString('zh-CN', { hour12: false });
}

/** 轮次状态中文名 */
function trailStatusLabel(status: string): string {
  if (status === 'completed') return '完成';
  if (status === 'pending_stop') return '已停车';
  if (status === 'aborted') return '中止';
  if (status === 'error') return '出错';
  return status;
}

/** 轮次耗时 */
function durationLabel(startMs: number, endMs: number | null): string {
  if (endMs === null || endMs < startMs) return '';
  const seconds = Math.round((endMs - startMs) / 1000);
  if (seconds < 60) return `${String(seconds)}s`;
  return `${String(Math.floor(seconds / 60))}m${String(seconds % 60)}s`;
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
function applyReplayEvent(event: SessionEventItem): void {
  const payload = event.payload;
  if (event.type === 'user/message') {
    messages.value.push({
      role: 'user',
      text: String(payload['content'] ?? ''),
      thinking: '',
      tools: [],
    });
    return;
  }
  if (event.type === 'assistant/message') {
    messages.value.push({
      role: 'assistant',
      text: String(payload['content'] ?? ''),
      thinking: String(payload['reasoning_content'] ?? ''),
      tools: replayTools(payload),
    });
    return;
  }
  if (event.type === 'tool.result') {
    const target = [...messages.value]
      .reverse()
      .find((item) => item.role === 'assistant' && item.tools.length > 0);
    if (target !== undefined) {
      applyToolResult(target, payload);
    }
    return;
  }
  const last = messages.value.at(-1);
  if (last === undefined) {
    return;
  }
  if (event.type === 'permission.required') {
    last.permission = {
      request_id: String(payload['request_id'] ?? ''),
      name: String(payload['name'] ?? ''),
      reason: String(payload['reason'] ?? ''),
    };
  } else if (event.type === 'permission/decided') {
    last.permission = undefined;
  }
}

/** 把事件重放成消息列表 */
function applyReplay(events: SessionEventItem[]): void {
  messages.value = [];
  awaitingNextStep = false;
  for (const event of events) {
    applyReplayEvent(event);
  }
}

// 一轮 = 过程组（各步骤的思考/文本/工具）+ 最终回复；对齐 DSH 的轮次过程折叠
interface TurnView {
  key: string;
  user: ChatMessage | null;
  steps: ChatMessage[];
  answer: ChatMessage | null;
  running: boolean;
}

/** 按用户消息切分轮次：最后一轮运行中；完成轮把末条无工具的助手消息作为最终回复 */
const turnViews = computed<TurnView[]>(() => {
  const views: TurnView[] = [];
  for (const message of messages.value) {
    if (message.role === 'user') {
      views.push({
        key: `turn-${String(views.length)}`,
        user: message,
        steps: [],
        answer: null,
        running: false,
      });
      continue;
    }
    const view = views.at(-1);
    if (view !== undefined) {
      view.steps.push(message);
    }
  }
  for (const [index, view] of views.entries()) {
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
  return views;
});

/** 展开/收起某一轮的过程组 */
function toggleProcess(key: string): void {
  const next = new Set(expandedTurns.value);
  if (next.has(key)) {
    next.delete(key);
  } else {
    next.add(key);
  }
  expandedTurns.value = next;
}

/** 工具结果回填：按调用顺序填第一个还没有结果的工具行 */ function applyToolResult(
  last: ChatMessage,
  payload: Record<string, unknown>,
): void {
  const target =
    last.tools.find((tool) => tool.result === undefined && tool.error === undefined) ??
    last.tools.at(-1);
  if (target === undefined) {
    return;
  }
  target.result = String(payload['result'] ?? '');
  target.error = payload['error'] ? String(payload['error']) : undefined;
}

/** 应用单条实时事件：assistant/message 一步一条，带工具调用则在下一步增量时另起消息块 */
function applyEvent(type: string, payload: Record<string, unknown>): void {
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
    });
  } else if (type === 'tool.result') {
    applyToolResult(last, payload);
  } else if (type === 'permission.required') {
    last.permission = {
      request_id: String(payload['request_id'] ?? ''),
      name: String(payload['name'] ?? ''),
      reason: String(payload['reason'] ?? ''),
    };
  } else if (type === 'permission/decided') {
    last.permission = undefined;
  } else if (type === 'error') {
    last.text += `\n[错误] ${String(payload['msg'] ?? payload['code'] ?? '')}`;
  } else if (type === 'turn.end' && String(payload['status'] ?? '') === 'pending_stop') {
    last.text += '\n（等待你的裁决）';
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

/** 发送消息：进入流式 */
function handleSend(): void {
  if (!current.value || !input.value.trim() || running.value) {
    return;
  }
  const content = input.value.trim();
  input.value = '';
  // 发出首条消息即转正：不再按草稿丢弃
  draftSessionId.value = null;
  awaitingNextStep = false;
  messages.value.push({ role: 'user', text: content, thinking: '', tools: [] });
  messages.value.push({ role: 'assistant', text: '', thinking: '', tools: [] });
  running.value = true;
  abortController = addTurnStream(currentId.value, content, {
    onEvent: applyEvent,
    onDone: () => {
      running.value = false;
      void loadUsage();
      void refreshSessions();
    },
    onError: (message) => {
      running.value = false;
      ElMessage.error(message);
    },
  });
}

/** 停止：结束流并通知后端 */
async function handleStop(): Promise<void> {
  abortController?.abort();
  abortController = null;
  running.value = false;
  await updateTurnStop(currentId.value);
  await loadUsage();
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
function decidePermission(
  item: ChatMessage,
  outcome: 'allowed-once' | 'allowed-always' | 'rejected',
): void {
  const permission = item.permission;
  if (!permission) {
    return;
  }
  item.permission = undefined;
  running.value = true;
  abortController = addTurnResume(currentId.value, permission.request_id, outcome, {
    onEvent: applyEvent,
    onDone: () => {
      running.value = false;
      void loadUsage();
    },
    onError: (message) => {
      running.value = false;
      ElMessage.error(message);
    },
  });
}

/** 切换本会话权限档 */
async function changePreset(value: string | number | boolean | undefined): Promise<void> {
  if (!current.value || typeof value !== 'string') {
    return;
  }
  await updateSessionPermission(currentId.value, value as PermissionPreset);
  current.value = { ...current.value, permission_preset: value };
}

/** 当前会话所在的工作区高亮 */
function isGroupActive(group: SessionGroup): boolean {
  return group.items.some((item) => item.id === currentId.value);
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
  if (currentId.value) {
    await loadSubSessions();
  }
});

onUnmounted(() => {
  stopSubPolling();
});
</script>

<style scoped lang="css">
.assistant-page {
  gap: 14px;
}

.sub-count {
  margin-left: 4px;
  padding: 0 6px;
  border-radius: 999px;
  background: var(--kb-primary);
  color: #06231a;
  font-size: 10px;
}

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

.chat-wrap {
  display: grid;
  flex: 1;
  min-height: 0;
  grid-template-columns: 280px minmax(0, 1fr);
  gap: 14px;
}

.sessions-panel,
.chat-panel {
  display: flex;
  min-height: 0;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--kb-line);
  border-radius: var(--kb-radius);
  background: var(--kb-bg-2);
}

.sessions-head {
  display: flex;
  flex: none;
  gap: 2px;
  align-items: center;
  padding: 10px 10px 10px 12px;
  border-bottom: 1px solid var(--kb-line);
}

.sessions-title {
  color: var(--kb-text-2);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.04em;
}

.head-icon {
  display: inline-flex;
  flex: none;
  width: 24px;
  height: 24px;
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
  background: rgb(255 255 255 / 6%);
  color: var(--kb-text-2);
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
  height: 34px;
  align-items: center;
  gap: 6px;
  padding: 0 6px 0 8px;
  border-radius: 8px;
  color: var(--kb-text-2);
  cursor: pointer;
  user-select: none;
}

.group-head:hover {
  background: rgb(255 255 255 / 5%);
}

.group-head-on {
  background: rgb(255 255 255 / 7%);
}

.group-folder {
  display: inline-flex;
  flex: none;
  width: 16px;
  align-items: center;
  justify-content: center;
  color: var(--kb-text-3);
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

.group-head-on .group-folder,
.group-head:hover .group-folder {
  display: none;
}

.group-head-on .group-arrow,
.group-head:hover .group-arrow {
  display: inline-block;
}

.group-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  font-size: 13px;
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

.group-head-on .group-actions,
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

/* 会话行：32px 单行；悬停换底色，时间让位给重命名（DSH 同款） */
.session-item {
  display: flex;
  height: 32px;
  width: 100%;
  align-items: center;
  gap: 6px;
  padding: 0 8px;
  border: none;
  border-radius: 8px;
  background: none;
  color: var(--kb-text-1);
  cursor: pointer;
  font-family: 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif;
  text-align: left;
}

.session-item:hover {
  background: rgb(255 255 255 / 5%);
}

.session-item-on {
  background: var(--kb-tint);
}

.session-item-on:hover {
  background: var(--kb-tint);
}

.session-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  font-size: 13px;
  line-height: 18px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-time {
  flex: none;
  color: var(--kb-text-3);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 10px;
}

.session-rename {
  display: none;
  flex: none;
  width: 18px;
  color: var(--kb-text-3);
  font-size: 11px;
  text-align: center;
}

.session-rename:hover {
  color: var(--kb-primary);
}

.session-delete {
  display: none;
  flex: none;
  width: 18px;
  color: var(--kb-text-3);
  font-size: 11px;
  text-align: center;
}

.session-delete:hover {
  color: var(--kb-danger);
}

.session-item:hover .session-time {
  display: none;
}

.session-item:hover .session-rename,
.session-item:hover .session-delete {
  display: block;
}

.session-group {
  margin-bottom: 2px;
}

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

.session-empty,
.messages-empty {
  padding: 18px 12px;
  color: var(--kb-text-3);
  font-size: 13px;
  text-align: center;
}

.chat-head {
  display: flex;
  flex: none;
  gap: 10px;
  align-items: center;
  padding: 12px 16px;
  border-bottom: 1px solid var(--kb-line);
}

.chat-title {
  font-size: 14px;
  font-weight: 650;
}

.chat-dir {
  padding: 2px 8px;
  border: 1px solid var(--kb-line);
  border-radius: 999px;
  background: var(--kb-surface);
  color: var(--kb-text-2);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 11px;
}

.chat-status {
  color: var(--kb-text-3);
  font-size: 12px;
}

.chat-usage {
  padding: 2px 8px;
  border: 1px solid var(--kb-line);
  border-radius: 999px;
  background: var(--kb-surface);
  color: var(--kb-text-2);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 11px;
}

/* 活动时间线：按轮次分组，文本/思考增量不入线 */
.trail {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.trajectory-empty {
  color: var(--kb-text-3);
  font-size: 13px;
}

.trail-turn {
  overflow: hidden;
  border: 1px solid var(--kb-line);
  border-radius: 10px;
}

.trail-head {
  display: flex;
  gap: 10px;
  align-items: center;
  padding: 8px 12px;
  background: rgb(255 255 255 / 4%);
  font-size: 12px;
}

.trail-index {
  color: var(--kb-text);
  font-weight: 600;
}

.trail-time,
.trail-duration,
.trail-node-time,
.trail-node-duration {
  color: var(--kb-text-3);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 11px;
}

.trail-status {
  margin-left: auto;
  padding: 1px 8px;
  border-radius: 999px;
  color: var(--kb-text-2);
  background: rgb(255 255 255 / 6%);
  font-size: 11px;
}

.trail-status[data-status='completed'] {
  color: var(--kb-ok);
}

.trail-status[data-status='aborted'],
.trail-status[data-status='error'] {
  color: var(--kb-danger);
}

.trail-status[data-status='pending_stop'] {
  color: #f59e0b;
}

.trail-node {
  padding: 8px 12px;
  border-top: 1px solid var(--kb-line);
}

.trail-line {
  display: flex;
  gap: 8px;
  align-items: baseline;
}

.trail-badge {
  flex: none;
  width: 34px;
  color: var(--kb-text-3);
  font-size: 11px;
}

.trail-title {
  flex: 1;
  min-width: 0;
  overflow-wrap: anywhere;
  font-size: 12px;
}

.trail-node[data-kind='tool'] .trail-badge {
  color: var(--kb-primary-2);
}

.trail-node[data-kind='error'] .trail-title {
  color: var(--kb-danger);
}

.trail-node[data-kind='call'] .trail-title {
  color: var(--kb-text-3);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 11px;
}

.trail-detail {
  margin: 6px 0 0 42px;
}

.trail-summary {
  color: var(--kb-text-3);
  cursor: pointer;
  font-size: 11px;
}

.trail-pre {
  margin: 6px 0 0;
  padding: 8px 10px;
  overflow-wrap: anywhere;
  border-radius: 6px;
  color: var(--kb-text-2);
  background: rgb(0 0 0 / 30%);
  font-size: 11px;
  white-space: pre-wrap;
}

/* 轮次 = 过程组 + 最终回复；过程组默认折叠（运行中展开） */
.turn {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.process {
  padding-left: 12px;
  border-left: 2px solid var(--kb-line);
}

.process-head {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 2px 0;
  border: none;
  color: var(--kb-text-2);
  background: transparent;
  cursor: pointer;
  font-size: 12px;
}

.process-title {
  font-weight: 600;
}

.process-meta,
.process-arrow {
  color: var(--kb-text-3);
  font-size: 11px;
}

.process-arrow {
  margin-left: auto;
}

.process-body {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 6px;
}

.step {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.message-answer {
  margin-top: 2px;
}

.messages {
  display: flex;
  flex: 1;
  min-height: 0;
  flex-direction: column;
  gap: 18px;
  overflow: auto;
  padding: 18px 22px;
}

.message {
  display: flex;
  max-width: 100%;
}

.message-bubble {
  min-width: 0;
  padding: 0;
  border: none;
  background: transparent;
  line-height: 1.7;
}

.message-user {
  justify-content: flex-end;
}

.message-user .message-bubble {
  max-width: 78%;
  padding: 10px 14px;
  border: 1px solid rgb(52 211 153 / 25%);
  border-radius: 10px;
  background: var(--kb-tint);
  white-space: pre-wrap;
}

.message-assistant .message-bubble {
  width: 100%;
}

.message-thinking {
  margin-bottom: 6px;
  color: var(--kb-text-3);
  font-size: 12px;
}

.thinking-summary {
  cursor: pointer;
  list-style: none;
}

.thinking-body {
  margin: 6px 0 4px;
  padding: 8px 10px;
  overflow-wrap: anywhere;
  border-left: 2px solid var(--kb-line-2);
  background: rgb(255 255 255 / 3%);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 11px;
  white-space: pre-wrap;
}

.tool-card {
  margin: 8px 0;
  overflow: hidden;
  border: 1px solid var(--kb-line);
  border-radius: 8px;
  background: rgb(255 255 255 / 3%);
}

.tool-head {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 6px 10px;
  cursor: pointer;
  font-size: 12px;
  list-style: none;
}

.tool-dot {
  flex: none;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--kb-text-3);
}

.tool-dot.is-ok {
  background: var(--kb-ok);
}

.tool-dot.is-fail {
  background: var(--kb-danger);
}

.tool-dot.is-run {
  background: #f59e0b;
}

.tool-name {
  color: var(--kb-primary-2);
  font-family: ui-monospace, Consolas, monospace;
}

.tool-args {
  overflow: hidden;
  color: var(--kb-text-3);
  font-family: ui-monospace, Consolas, monospace;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tool-state {
  margin-left: auto;
  flex: none;
  color: var(--kb-text-3);
  font-size: 11px;
}

.tool-card.tool-fail .tool-state {
  color: var(--kb-danger);
}

.tool-body {
  padding: 0 10px 10px;
  border-top: 1px solid var(--kb-line);
}

.tool-label {
  margin: 8px 0 4px;
  color: var(--kb-text-3);
  font-size: 11px;
}

.tool-pre {
  max-height: 260px;
  margin: 0;
  padding: 8px 10px;
  overflow: auto;
  overflow-wrap: anywhere;
  border-radius: 6px;
  color: var(--kb-text-2);
  background: rgb(0 0 0 / 30%);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 11px;
  white-space: pre-wrap;
}

.tool-error {
  color: var(--kb-danger);
}

.message-perm {
  margin-top: 8px;
  padding: 10px 12px;
  border: 1px solid rgb(251 191 36 / 40%);
  border-radius: 12px;
  background: rgb(251 191 36 / 8%);
}

.perm-title {
  color: var(--kb-warn);
  font-size: 13px;
  font-weight: 600;
}

.perm-reason {
  margin: 6px 0 10px;
  color: var(--kb-text-2);
  font-size: 13px;
}

.perm-actions {
  display: flex;
  gap: 8px;
}

.composer {
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
  padding: 0;
  border: none;
  background: none;
  color: var(--kb-primary);
  cursor: pointer;
  font-family: 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif;
  font-size: 12px;
}

.preset-box {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.preset-title {
  margin: 0;
  color: var(--kb-text-3);
  font-size: 12px;
}

.preset-note {
  margin: 0;
  color: var(--kb-text-3);
  font-size: 11px;
}
</style>
