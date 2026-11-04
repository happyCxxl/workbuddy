<template>
  <button v-if="hiddenTurns > 0" class="turn-earlier" type="button" @click="emit('show-earlier')">
    显示更早的 {{ hiddenTurns }} 轮
  </button>
  <section v-for="view in turns" :key="view.key" class="turn">
    <div
      v-for="(userMessage, userIndex) in view.userMessages"
      :key="`u-${String(userIndex)}`"
      class="message message-user"
    >
      <div class="message-bubble">{{ userMessage.text }}</div>
    </div>
    <div v-if="view.steps.length > 0" class="process">
      <button
        class="process-head"
        :class="{ 'is-running': view.running }"
        type="button"
        @click="emit('toggle-process', view.key)"
      >
        <span class="process-chevron">{{ isOpen(view) ? '▾' : '▸' }}</span>
        <span class="process-title">{{ view.running ? '执行中' : '工作步骤' }}</span>
        <span class="process-meta">
          {{ view.steps.length }} 步
          <template v-if="turnMeta[view.turnSeq ?? 0]?.duration">
            · {{ turnMeta[view.turnSeq ?? 0]?.duration }}
          </template>
          <template v-if="turnMeta[view.turnSeq ?? 0]?.tokens">
            · {{ turnMeta[view.turnSeq ?? 0]?.tokens }} tok
          </template>
        </span>
      </button>
      <div v-show="isOpen(view)" class="process-body">
        <div v-for="(step, stepIndex) in view.steps" :key="stepIndex" class="step">
          <div v-if="view.steps.length > 1" class="step-label">第 {{ stepIndex + 1 }} 步</div>
          <details v-if="step.thinking" class="reasoning">
            <summary class="reasoning-head">
              <span class="reasoning-label">思考</span>
              <span class="reasoning-preview">{{ preview(step.thinking, 80) }}</span>
            </summary>
            <pre class="reasoning-body">{{ step.thinking }}</pre>
          </details>
          <MarkdownView v-if="step.text" :content="step.text" />
          <details
            v-for="(tool, toolIndex) in step.tools"
            :key="toolIndex"
            class="tool-card"
            :class="{ 'tool-fail': Boolean(tool.error) }"
          >
            <summary class="tool-head">
              <ToolIcon :name="tool.name" class="tool-icon" />
              <span class="tool-name">{{ tool.name }}</span>
              <span class="tool-args">{{ tool.args }}</span>
              <span class="tool-state">
                {{ tool.error ? '失败' : tool.result ? '完成' : '执行中' }}
                <template v-if="tool.duration"> · {{ tool.duration }}</template>
              </span>
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
                @click="emit('decide-permission', step, 'allowed-once')"
              >
                允许一次
              </el-button>
              <el-button
                size="small"
                type="danger"
                plain
                @click="emit('decide-permission', step, 'rejected')"
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
    <div v-if="view.answer && !view.running" class="turn-footer">
      <span class="turn-actions">
        <el-popover placement="top-start" width="300" trigger="click">
          <template #reference>
            <button class="turn-icon-btn" type="button" title="本轮用量">
              <svg
                class="turn-icon"
                width="14"
                height="14"
                viewBox="0 0 16 16"
                fill="none"
                stroke="currentColor"
                stroke-width="1.4"
              >
                <ellipse cx="8" cy="3.5" rx="5" ry="2" />
                <path d="M3 3.5v9c0 1.1 2.2 2 5 2s5-.9 5-2v-9" />
                <path d="M3 8c0 1.1 2.2 2 5 2s5-.9 5-2" />
              </svg>
            </button>
          </template>
          <UsagePopover
            title="本轮用量"
            :total="(turnMeta[view.turnSeq ?? 0]?.tokens ?? 0).toLocaleString()"
            :model="turnModels(view)"
            :cache-rate="turnCacheRate(view)"
            :cache-miss="turnMeta[view.turnSeq ?? 0]?.cacheMiss ?? 0"
            :cache-hit="turnMeta[view.turnSeq ?? 0]?.cacheHit ?? 0"
            :completion="turnMeta[view.turnSeq ?? 0]?.completion ?? 0"
          />
        </el-popover>

        <button class="turn-icon-btn" type="button" title="复制" @click="copyAnswer(view)">
          <svg
            width="14"
            height="14"
            viewBox="0 0 16 16"
            fill="none"
            stroke="currentColor"
            stroke-width="1.4"
          >
            <rect x="5.5" y="5.5" width="8" height="8" rx="1" />
            <path d="M10.5 5.5V3.5a1 1 0 0 0-1-1h-6a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2" />
          </svg>
        </button>

        <el-popover
          v-if="!isLastTurn(view) && checkpointForTurn(view.turnSeq)"
          placement="top"
          width="240"
          trigger="click"
        >
          <template #reference>
            <button class="turn-icon-btn" type="button" title="回退操作">
              <svg
                width="14"
                height="14"
                viewBox="0 0 16 16"
                fill="none"
                stroke="currentColor"
                stroke-width="1.4"
              >
                <path d="M3 8a5 5 0 1 0 1.6-3.7" />
                <path d="M3 3v4h4" />
              </svg>
            </button>
          </template>
          <div class="rewind-menu">
            <button class="rewind-option" type="button" @click="emit('rewind', view, 'both')">
              回退操作（文件 + 对话）
            </button>
            <button class="rewind-option" type="button" @click="emit('rewind', view, 'code')">
              只回退文件操作
            </button>
            <button
              class="rewind-option"
              type="button"
              @click="emit('rewind', view, 'conversation')"
            >
              只回退对话
            </button>
          </div>
        </el-popover>
      </span>
    </div>
  </section>
</template>

<script setup lang="ts">
import { ElMessage } from 'element-plus';

import type { SessionCheckpoint } from '@/api/assistant';
import MarkdownView from '@/components/assistant/MarkdownView.vue';
import ToolIcon from '@/components/assistant/ToolIcon.vue';
import UsagePopover from '@/components/common/UsagePopover.vue';
import type { ChatMessage, TurnUsage, TurnView } from '@/types/chat';
import { prettyJson, preview } from '@/utils/chat-format';

const props = defineProps<{
  /** 当前渲染窗口内的轮次 */
  turns: TurnView[];
  /** 被窗口截掉的更早轮次数 */
  hiddenTurns: number;
  /** 每轮耗时、token 与用量汇总 */
  turnMeta: Partial<Record<number, TurnUsage>>;
  /** 用户展开的过程组 key 集合 */
  expandedKeys: Set<string>;
  /** 会话检查点：决定哪些轮可回退 */
  checkpoints: SessionCheckpoint[];
}>();

const emit = defineEmits<{
  'show-earlier': [];
  'toggle-process': [key: string];
  'decide-permission': [step: ChatMessage, outcome: 'allowed-once' | 'rejected'];
  rewind: [view: TurnView, mode: 'both' | 'code' | 'conversation'];
}>();

/** 某回合对应的检查点 */
function checkpointForTurn(turnSeq: number | undefined): SessionCheckpoint | undefined {
  if (turnSeq === undefined) {
    return undefined;
  }
  return props.checkpoints.find((item) => item.turn_seq === turnSeq);
}

/** 是否为最后一轮：最后一轮不显示回退 */
function isLastTurn(view: TurnView): boolean {
  return props.turns.at(-1)?.key === view.key;
}

/** 过程组当前是否展开：运行中强制显示，其余按用户展开状态 */
function isOpen(view: TurnView): boolean {
  return view.running || props.expandedKeys.has(view.key);
}

/** 本轮涉及的模型列表 */
function turnModels(view: TurnView): string {
  const entry = props.turnMeta[view.turnSeq ?? 0];
  if (!entry || entry.models.size === 0) {
    return '—';
  }
  return [...entry.models].join('、');
}

/** 本轮缓存命中率 */
function turnCacheRate(view: TurnView): number {
  const entry = props.turnMeta[view.turnSeq ?? 0];
  if (!entry || entry.prompt === 0) {
    return 0;
  }
  return Math.round((entry.cacheHit / entry.prompt) * 100);
}

/** 复制最终回复 */
function copyAnswer(view: TurnView): void {
  if (!view.answer) {
    return;
  }
  void navigator.clipboard.writeText(view.answer.text).then(() => {
    ElMessage.success('已复制');
  });
}
</script>

<style scoped>
/* 轮次 = 过程组 + 最终回复；过程组默认折叠（运行中展开） */
.turn {
  display: flex;
  flex-direction: column;
  gap: 12px;
  width: 100%;
  content-visibility: auto;
  contain-intrinsic-size: auto 200px;
}

.process {
  padding-left: 14px;
  border-left: 2px solid var(--kb-line);
}

.process-head {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-left: -6px;
  padding: 4px 6px;
  border: none;
  border-radius: 6px;
  color: var(--kb-text-2);
  background: transparent;
  cursor: pointer;
  font-size: 12px;
}

.process-head:hover {
  background: rgb(255 255 255 / 4%);
}

.process-chevron {
  width: 10px;
  color: var(--kb-text-3);
  font-size: 10px;
}

.process-title {
  font-weight: 600;
}

.process-meta {
  color: var(--kb-text-3);
  font-size: 11px;
}

.process-body {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 8px;
}

.step {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.message-answer {
  margin-top: 4px;
}

.turn-earlier {
  align-self: center;
  padding: 4px 12px;
  border: 1px solid var(--kb-line);
  border-radius: 999px;
  color: var(--kb-text-3);
  background: transparent;
  cursor: pointer;
  font-size: 11px;
}

.turn-earlier:hover {
  color: var(--kb-text-2);
  background: rgb(255 255 255 / 4%);
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
  line-height: 1.8;
}

.message-user {
  justify-content: flex-end;
}

.message-user .message-bubble {
  max-width: 60%;
  padding: 6px 10px;
  border: 1px solid rgb(52 211 153 / 18%);
  border-radius: 10px;
  background: rgb(52 211 153 / 8%);
  font-size: 14px;
  line-height: 1.7;
  white-space: pre-wrap;
}

.message-assistant .message-bubble {
  width: 100%;
}

.process-head.is-running .process-title {
  background: linear-gradient(90deg, var(--kb-text-2), #34d399, var(--kb-text-2));
  background-size: 200% 100%;
  animation: process-shimmer 1.6s linear infinite;
  background-clip: text;
  color: transparent;
}

@keyframes process-shimmer {
  from {
    background-position: 200% 0;
  }

  to {
    background-position: 0 0;
  }
}

.step-label {
  color: var(--kb-text-3);
  font-size: 11px;
}

.reasoning {
  margin: 0;
}

.reasoning-head {
  display: flex;
  gap: 8px;
  align-items: baseline;
  cursor: pointer;
  list-style: none;
}

.reasoning-label {
  flex: none;
  color: var(--kb-text-3);
  font-size: 11px;
}

.reasoning-preview {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  color: var(--kb-text-3);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.reasoning-body {
  margin: 6px 0 4px;
  padding: 8px 10px;
  overflow-wrap: anywhere;
  border-left: 2px solid var(--kb-line-2);
  background: rgb(255 255 255 / 3%);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 11px;
  white-space: pre-wrap;
}

.turn-footer {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  padding-left: 2px;
  opacity: 0;
  transition: opacity 0.15s ease;
}

.turn-footer:focus-within,
.turn:last-child .turn-footer,
.turn:hover .turn-footer {
  opacity: 1;
}

@media (hover: none) {
  .turn-footer {
    opacity: 1;
  }
}

.turn-actions {
  display: flex;
  gap: 4px;
  align-items: center;
  margin-right: auto;
}

.turn-icon-btn {
  display: grid;
  width: 24px;
  height: 24px;
  place-items: center;
  padding: 0;
  border: 1px solid transparent;
  border-radius: 3px;
  color: var(--kb-text-3);
  background: transparent;
  cursor: pointer;
}

.turn-icon-btn:hover {
  border-color: var(--kb-line-2);
  color: var(--kb-primary);
  background: rgb(34 224 255 / 6%);
}

.turn-icon {
  width: 14px;
  height: 14px;
}

.rewind-menu {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.rewind-option {
  padding: 7px 8px;
  border: 0;
  border-radius: 2px;
  color: var(--kb-text-2);
  background: transparent;
  text-align: left;
  cursor: pointer;
}

.rewind-option:hover {
  color: var(--kb-text-1);
  background: rgb(34 224 255 / 8%);
}

.tool-card {
  margin: 2px 0;
  border: none;
  border-radius: 6px;
  background: transparent;
}

.tool-head {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-left: -6px;
  padding: 3px 6px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 12px;
  list-style: none;
}

.tool-head:hover {
  background: rgb(255 255 255 / 4%);
}

.tool-icon {
  flex: none;
  width: 12px;
  height: 12px;
  color: var(--kb-text-3);
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
  margin-left: 12px;
  padding: 2px 0 8px;
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
</style>
