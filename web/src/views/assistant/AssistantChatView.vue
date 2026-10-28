<template>
  <div class="page assistant-page">
    <div class="page-head">
      <div>
        <h1 class="page-title">对话</h1>
        <p class="page-desc">
          和可乐一起干活：它会读写你的工作目录、跑命令、查资料，高危操作先问过你。
        </p>
      </div>
      <div class="page-actions">
        <el-button class="page-btn-ghost" plain @click="newDialogVisible = true">
          ＋ 新建会话
        </el-button>
      </div>
    </div>

    <div class="chat-wrap">
      <!-- 会话列表 -->
      <div class="sessions-panel">
        <div class="sessions-head">
          <span class="sessions-title">会话</span>
          <button class="page-refresh" type="button" title="刷新" @click="loadSessions">⟳</button>
        </div>
        <div class="sessions-list">
          <button
            v-for="item in sessions"
            :key="item.id"
            type="button"
            class="session-item"
            :class="{ 'session-item-on': item.id === currentId }"
            @click="selectSession(item)"
          >
            <span class="session-title">{{ item.title || '未命名会话' }}</span>
            <span class="session-dir">{{ item.work_dir }}</span>
            <span class="session-time"
              >{{ presetLabel(item.permission_preset) }} · {{ formatTime(item.updated_at) }}</span
            >
          </button>
          <p v-if="sessions.length === 0" class="session-empty">还没有会话，点右上角新建。</p>
        </div>
      </div>

      <!-- 对话区 -->
      <div class="chat-panel">
        <div class="chat-head">
          <span class="chat-title">{{ current?.title || '选择或新建一个会话' }}</span>
          <span v-if="current" class="chat-dir">{{ current.work_dir }}</span>
          <span class="page-spacer"></span>
          <span v-if="running" class="chat-status">执行中 · 第 {{ stepLabel }} 步</span>
        </div>

        <div ref="messageBox" class="messages">
          <p v-if="messages.length === 0" class="messages-empty">
            发一句话开始干活。可乐会按人格版本 {{ personaLabel }} 说话，按权限档执行工具。
          </p>
          <div
            v-for="(item, index) in messages"
            :key="index"
            :class="['message', item.role === 'user' ? 'message-user' : 'message-assistant']"
          >
            <div class="message-bubble">
              <details v-if="item.thinking" class="message-thinking">
                <summary class="thinking-summary">已思考（点击展开）</summary>
                <p class="thinking-body">{{ item.thinking }}</p>
              </details>
              <p v-if="item.text" class="message-text">{{ item.text }}</p>
              <div v-for="(tool, toolIndex) in item.tools" :key="toolIndex" class="message-tool">
                <span class="tool-name">{{ tool.name }}</span>
                <span class="tool-args">{{ tool.args }}</span>
                <span v-if="tool.error" class="tool-fail">失败：{{ tool.error }}</span>
                <span v-else-if="tool.result" class="tool-ok">成功</span>
                <span v-else class="tool-run">执行中…</span>
              </div>
              <div v-if="item.permission" class="message-perm">
                <div class="perm-title">⚠ 需要你确认：{{ item.permission.name }}</div>
                <div class="perm-reason">{{ item.permission.reason }}</div>
                <div class="perm-actions">
                  <el-button
                    size="small"
                    type="primary"
                    @click="decidePermission(item, 'allowed-once')"
                  >
                    允许一次
                  </el-button>
                  <el-button size="small" @click="decidePermission(item, 'allowed-always')">
                    始终允许（本目录）
                  </el-button>
                  <el-button
                    size="small"
                    type="danger"
                    plain
                    @click="decidePermission(item, 'rejected')"
                  >
                    拒绝
                  </el-button>
                </div>
              </div>
            </div>
          </div>
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

    <el-dialog v-model="newDialogVisible" title="新建会话" width="440px" destroy-on-close>
      <el-form label-position="top">
        <el-form-item label="工作目录（会话内的文件操作限定在此目录）">
          <el-input v-model="newWorkDir" placeholder="D:\projects\workbuddy" />
        </el-form-item>
        <el-form-item label="会话标题">
          <el-input v-model="newTitle" placeholder="例如：修 pom 依赖冲突" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="newDialogVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!newWorkDir.trim()" @click="handleCreate">
          创建
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ElMessage } from 'element-plus';
import { nextTick, onMounted, ref } from 'vue';

import {
  addAssistantSession,
  addTurnResume,
  addTurnStream,
  getAssistantSessions,
  getSessionEvents,
  updateSessionPermission,
  updateTurnStop,
} from '@/api/assistant';
import type { AssistantSession, PermissionPreset, SessionEventItem } from '@/types/assistant';
import { formatDateTime } from '@/utils/date';

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
const messages = ref<ChatMessage[]>([]);
const input = ref('');
const running = ref(false);
const stepLabel = ref('1');
const personaLabel = ref('v1');
const messageBox = ref<HTMLElement | null>(null);
const newDialogVisible = ref(false);
const newWorkDir = ref('');
const newTitle = ref('');
let abortController: AbortController | null = null;

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

/** 载入会话列表 */
async function loadSessions(): Promise<void> {
  sessions.value = await getAssistantSessions();
  const first = sessions.value.at(0);
  if (!currentId.value && first !== undefined) {
    await selectSession(first);
  }
}

/** 选择会话：重放事件重建消息 */
async function selectSession(item: AssistantSession): Promise<void> {
  currentId.value = item.id;
  current.value = item;
  messages.value = [];
  const events = await getSessionEvents(item.id);
  applyReplay(events);
  await scrollToBottom();
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

/** 重放单条事件：只投影用户消息、助手消息与审批卡 */
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
  for (const event of events) {
    applyReplayEvent(event);
  }
}

/** 工具结果回填到最近一个工具行 */
function applyToolResult(last: ChatMessage, payload: Record<string, unknown>): void {
  const target = last.tools.at(-1);
  if (target === undefined) {
    return;
  }
  target.result = String(payload['result'] ?? '');
  target.error = payload['error'] ? String(payload['error']) : undefined;
}

/** 应用单条实时事件 */
function applyEvent(type: string, payload: Record<string, unknown>): void {
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
  messages.value.push({ role: 'user', text: content, thinking: '', tools: [] });
  messages.value.push({ role: 'assistant', text: '', thinking: '', tools: [] });
  running.value = true;
  abortController = addTurnStream(currentId.value, content, {
    onEvent: applyEvent,
    onDone: () => {
      running.value = false;
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

/** 新建会话 */
async function handleCreate(): Promise<void> {
  if (!newWorkDir.value.trim()) {
    return;
  }
  const created = await addAssistantSession(newWorkDir.value.trim(), newTitle.value.trim());
  sessions.value.unshift(created);
  newDialogVisible.value = false;
  newWorkDir.value = '';
  newTitle.value = '';
  await selectSession(created);
}

onMounted(async () => {
  await loadSessions();
});
</script>

<style scoped lang="css">
.assistant-page {
  gap: 14px;
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
  align-items: center;
  justify-content: space-between;
  padding: 12px 14px;
  border-bottom: 1px solid var(--kb-line);
}

.sessions-title {
  color: var(--kb-text-2);
  font-size: 13px;
}

.sessions-list {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 8px;
}

.session-item {
  display: flex;
  width: 100%;
  flex-direction: column;
  gap: 4px;
  padding: 10px 12px;
  border: 1px solid transparent;
  border-radius: 10px;
  background: none;
  color: var(--kb-text-1);
  cursor: pointer;
  font-family: 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif;
  text-align: left;
}

.session-item-on {
  border-color: rgb(52 211 153 / 25%);
  background: var(--kb-tint);
}

.session-title {
  font-size: 13px;
  font-weight: 600;
}

.session-dir,
.session-time {
  overflow: hidden;
  color: var(--kb-text-3);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 11px;
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

.messages {
  display: flex;
  flex: 1;
  min-height: 0;
  flex-direction: column;
  gap: 16px;
  overflow: auto;
  padding: 18px 20px;
}

.message {
  display: flex;
  max-width: 86%;
}

.message-bubble {
  padding: 10px 14px;
  border: 1px solid var(--kb-line);
  border-radius: var(--kb-radius);
  background: var(--kb-surface);
  line-height: 1.7;
}

.message-user .message-bubble {
  border-color: rgb(52 211 153 / 30%);
  background: var(--kb-tint);
}

.message-text {
  margin: 4px 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font-size: 13px;
}

.message-thinking {
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
  border-left: 2px solid var(--kb-line-2);
  background: rgb(255 255 255 / 3%);
}

.message-tool {
  display: flex;
  gap: 8px;
  align-items: center;
  margin: 6px 0;
  padding: 6px 10px;
  border: 1px solid var(--kb-line);
  border-radius: 8px;
  background: rgb(255 255 255 / 3%);
  font-size: 12px;
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

.tool-ok {
  margin-left: auto;
  color: var(--kb-ok);
}

.tool-fail {
  margin-left: auto;
  color: var(--kb-danger);
}

.tool-run {
  margin-left: auto;
  color: var(--kb-text-3);
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
