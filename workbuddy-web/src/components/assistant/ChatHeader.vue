<template>
  <div class="chat-head">
    <button
      class="head-icon"
      type="button"
      :title="props.sessionsOpen ? '收起会话栏' : '展开会话栏'"
      @click="emit('toggle-sessions')"
    >
      <svg
        width="16"
        height="16"
        viewBox="0 0 16 16"
        fill="none"
        stroke="currentColor"
        stroke-width="1.5"
      >
        <path d="M2.5 4h11M2.5 8h11M2.5 12h11" />
      </svg>
    </button>
    <span class="chat-title">{{ props.title }}</span>
    <span v-if="props.workDir" class="chat-dir">{{ props.workDir }}</span>
    <span class="page-spacer"></span>
    <el-button v-if="props.hasSession" size="small" text @click="emit('open-trajectory')">
      轨迹
    </el-button>
    <el-button v-if="props.hasSession" size="small" text @click="emit('open-sub-sessions')">
      后台任务
      <span v-if="props.runningSubCount > 0" class="sub-count">{{ props.runningSubCount }}</span>
    </el-button>
    <span v-if="props.running" class="chat-status">执行中 · 第 {{ props.stepLabel }} 步</span>
  </div>
</template>

<script setup lang="ts">
const props = defineProps<{
  sessionsOpen: boolean;
  title: string;
  workDir: string | null;
  hasSession: boolean;
  runningSubCount: number;
  running: boolean;
  stepLabel: string | number;
}>();

const emit = defineEmits<{
  'toggle-sessions': [];
  'open-trajectory': [];
  'open-sub-sessions': [];
}>();
</script>

<style scoped lang="css">
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
  padding: 4px 12px;
  border: 1px solid var(--kb-line-2);
  border-left: 2px solid var(--kb-primary);
  clip-path: polygon(7px 0, 100% 0, 100% calc(100% - 7px), calc(100% - 7px) 100%, 0 100%, 0 7px);
  background: linear-gradient(90deg, var(--kb-tint), transparent 70%);
  color: var(--kb-text-2);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 12px;
  letter-spacing: 0.02em;
  box-shadow: inset 0 0 14px var(--kb-tint);
}

.chat-status {
  color: var(--kb-text-3);
  font-size: 12px;
}

.sub-count {
  margin-left: 4px;
  padding: 0 6px;
  border-radius: 999px;
  background: var(--kb-primary);
  color: #06231a;
  font-size: 10px;
}

/* 与左侧会话栏共用图标按钮外观；组件内需自带一份（scoped 不跨组件生效） */
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
</style>
