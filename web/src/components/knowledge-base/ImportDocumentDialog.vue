<template>
  <el-dialog
    v-model="visible"
    title="导入文档"
    width="780px"
    :close-on-click-modal="false"
    @closed="onClosed"
  >
    <!-- 左右两栏：左栏选知识库（可搜索、可滚动），右栏放文件。
         库和文件同时可见，一步完成——库多时左栏滚动即可，不挤占文件区 -->
    <div class="import-cols">
      <!-- 左：知识库 -->
      <div class="import-kb-col">
        <div class="import-kb-head">
          <span class="import-kb-title">选择知识库</span>
          <span class="import-kb-count">{{ enabledKbs.length }} 个</span>
        </div>
        <el-input
          v-model="kbKeyword"
          class="import-kb-search"
          placeholder="搜索名称"
          clearable
          size="small"
        />
        <div class="import-kb-list">
          <button
            v-for="item in filteredKbs"
            :key="item.id"
            class="import-kb-item"
            :class="{ 'is-on': item.id === pickedKbId }"
            type="button"
            :disabled="running"
            @click="pickedKbId = item.id"
          >
            <i class="import-kb-dot" :class="{ 'is-off': item.status !== KB_STATUS_ACTIVE }"></i>
            <span class="import-kb-main">
              <span class="import-kb-name" :title="item.name">{{ item.name }}</span>
              <span class="import-kb-meta">
                <span v-if="item.status !== KB_STATUS_ACTIVE">已停用 · </span>
                {{ item.documentCount ?? 0 }} 篇
              </span>
            </span>
          </button>
          <div v-if="kbLoading" class="import-kb-empty">加载中…</div>
          <div v-else-if="filteredKbs.length === 0" class="import-kb-empty">
            {{ enabledKbs.length === 0 ? '没有可用的知识库' : '没有匹配的知识库' }}
          </div>
        </div>
      </div>

      <!-- 右：文件 -->
      <div class="import-file-col">
        <div class="import-target">
          <span class="import-target-label">导入到</span>
          <span v-if="pickedKb" class="import-target-name">{{ pickedKb.name }}</span>
          <span v-else class="import-target-none">请先在左侧选择知识库</span>
        </div>

        <div
          class="import-drop"
          :class="{ 'is-over': dragOver, 'is-disabled': running || !pickedKb }"
          @click="pickFiles"
          @dragover.prevent="dragOver = true"
          @dragleave.prevent="dragOver = false"
          @drop.prevent="onDrop"
        >
          <span class="import-drop-icon">＋</span>
          <span class="import-drop-text">点击选择文件，或拖拽到此处</span>
          <span class="import-drop-hint">
            支持 {{ ACCEPTED_FILE_EXTENSIONS.join(' / ') }}，单文件不超过 100MB
          </span>
          <input
            ref="fileInput"
            class="import-file-input"
            type="file"
            multiple
            :accept="FILE_ACCEPT_ATTR"
            @change="onInputChange"
          />
        </div>

        <!-- 待导入列表：每个文件独立显示自己的结果（提交接口对校验失败返回 FAIL 日志而非报错） -->
        <div v-if="items.length > 0" class="import-list">
          <div v-for="item in items" :key="item.key" class="import-item">
            <span
              class="import-item-dot"
              :class="{
                'state-uploading': item.state === 'uploading',
                'state-submitting': item.state === 'submitting',
                'state-passed': item.state === 'passed',
                'state-failed': item.state === 'failed',
              }"
            ></span>
            <div class="import-item-main">
              <div class="import-item-name" :title="item.file.name">{{ item.file.name }}</div>
              <div class="import-item-meta" :class="{ 'is-err': item.state === 'failed' }">
                {{ describe(item) }}
              </div>
            </div>
            <span class="import-item-size">{{ formatBytes(item.file.size) }}</span>
            <button
              v-if="!running && item.state !== 'passed'"
              class="import-item-remove"
              type="button"
              title="移除"
              @click="removeItem(item.key)"
            >
              ×
            </button>
          </div>
        </div>

        <!-- 完成后：说明下一步去哪（建档后需到执行链页手动触发解析） -->
        <div v-if="doneSummary" class="import-summary">
          {{ doneSummary }}
          <span class="import-summary-next">
            建档完成但不会自动处理——到执行链页逐个环节手动触发
          </span>
        </div>
      </div>
    </div>

    <template #footer>
      <el-button :disabled="running" @click="visible = false">关闭</el-button>
      <el-button type="primary" :loading="running" :disabled="!canSubmit" @click="startImport">
        {{ running ? '导入中…' : `开始导入${items.length > 0 ? `（${items.length}）` : ''}` }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';

import { addFile, addFileSubmit } from '@/api/file';
import { getKnowledgeBasePage } from '@/api/knowledge-base';
import {
  ACCEPTED_FILE_EXTENSIONS,
  FILE_ACCEPT_ATTR,
  checkFileBeforeUpload,
  formatBytes,
} from '@/types/file';
import { KB_STATUS_ACTIVE } from '@/types/knowledge-base';
import type { KnowledgeBase } from '@/types/knowledge-base';

// 导入文档：上传到文件中心拿 fileId → 提交到知识库建档。
// 只建档不建任务（手动逐环节口径）：完成后引导去执行链页触发解析
const visible = defineModel<boolean>({ required: true });

const props = defineProps<{
  /** 从卡片进入时预选的知识库 ID */
  presetKbId?: string;
}>();

const emit = defineEmits<{
  /** 全部处理结束（无论成败），父组件据此刷新列表 */
  finished: [];
}>();

type ItemState = 'pending' | 'uploading' | 'submitting' | 'passed' | 'failed';

interface ImportItem {
  key: string;
  file: File;
  /**
   * 幂等键：**一次提交一个**（后端契约见 `FileSubmitDTO.requestId`：提交端生成、通常 UUID、
   * 网络重试沿用同一值）。
   *
   * <p>加入列表时生成并挂在本项上——本项的重试沿用同一个值（超时重发不会重复建档），
   * 而"同一份文件再导入一次"是**新的一次提交**：移出列表再加回、或重开弹窗都会拿到新键，
   * 照常新建档。**不能由文件名/大小/修改时间推导** —— 那样同一份文件永远撞同一个键。
   */
  requestId: string;
  state: ItemState;
  /** 上传进度百分比 */
  percent: number;
  /** 失败原因；预检不通过或后端 failReason */
  reason: string;
  /** 前端预检就不通过：不发起上传，只展示原因 */
  precheckFailed: boolean;
}

const fileInput = ref<HTMLInputElement | null>(null);
const items = ref<ImportItem[]>([]);
const kbs = ref<KnowledgeBase[]>([]);
const kbLoading = ref(false);
const pickedKbId = ref('');
const kbKeyword = ref('');
const dragOver = ref(false);
const running = ref(false);
const doneSummary = ref('');
let keySeed = 0;

/**
 * 一次拉取的知识库条数上限。
 *
 * <p>拉全量再在前端搜索（不翻页）：导入场景需要看到完整候选。
 * 取 500 条：100 条不足以覆盖真实使用，库多了会静默漏掉。
 */
const KB_FETCH_SIZE = 500;

/** 左栏列出全部知识库（含停用）：停用的标灰禁选 */
const filteredKbs = computed(() => {
  const keyword = kbKeyword.value.trim().toLowerCase();
  if (!keyword) {
    return kbs.value;
  }
  return kbs.value.filter((kb) => kb.name.toLowerCase().includes(keyword));
});

const enabledKbs = computed(() => kbs.value.filter((kb) => kb.status === KB_STATUS_ACTIVE));

/** 仅用于右下角「导入到 xxx」展示 */
const pickedKb = computed(() => kbs.value.find((kb) => kb.id === pickedKbId.value) ?? null);

const canSubmit = computed(
  () => pickedKbId.value !== '' && items.value.length > 0 && !running.value,
);

function describe(item: ImportItem): string {
  switch (item.state) {
    case 'pending':
      return '待导入';
    case 'uploading':
      return `上传中 ${item.percent}%`;
    case 'submitting':
      return '建档中…';
    case 'passed':
      return '已建档';
    default:
      return item.reason || '导入失败';
  }
}

function pickFiles(): void {
  if (!running.value) {
    fileInput.value?.click();
  }
}

function onInputChange(event: Event): void {
  const input = event.target as HTMLInputElement;
  addFiles(Array.from(input.files ?? []));
  // 清空 value：连续选同一个文件也要触发 change
  input.value = '';
}

function onDrop(event: DragEvent): void {
  dragOver.value = false;
  if (running.value) {
    return;
  }
  addFiles(Array.from(event.dataTransfer?.files ?? []));
}

/** 加入待导入列表；预检不通过的也列出来，让用户看到原因而不是静默丢弃 */
function addFiles(files: File[]): void {
  for (const file of files) {
    keySeed += 1;
    const reason = checkFileBeforeUpload(file);
    items.value.push({
      key: `f${keySeed}`,
      file,
      // 每次"加入待导入列表"就是一次新的提交意图，键在这里生成一次
      requestId: newRequestId(),
      state: reason ? 'failed' : 'pending',
      percent: 0,
      reason: reason ?? '',
      precheckFailed: reason !== null,
    });
  }
}

function removeItem(key: string): void {
  items.value = items.value.filter((item) => item.key !== key);
}

/**
 * 逐个文件导入。
 *
 * <p>逐个文件串行导入（不并发）。
 *
 * <p>提交接口对「文件校验不通过」**不抛异常**，而是返回 status=FAIL 的提交日志；
 * 这里必须检查 submitLog.status。
 */
async function startImport(): Promise<void> {
  running.value = true;
  doneSummary.value = '';
  let passed = 0;
  let failed = 0;

  for (const item of items.value) {
    // 已成功的跳过：失败后重试整批不重复建档（幂等键兜住）
    if (item.state === 'passed') {
      passed += 1;
      continue;
    }
    // 前端预检就没过的，不浪费一次上传
    if (item.precheckFailed) {
      failed += 1;
      continue;
    }
    try {
      item.state = 'uploading';
      item.percent = 0;
      const fileId = await addFile(item.file, (percent) => {
        item.percent = percent;
      });

      item.state = 'submitting';
      // 幂等键取自本项（生成于加入列表时）：本项重试沿用同一个值，不会重复建档
      const result = await addFileSubmit(pickedKbId.value, fileId, item.requestId);

      if (result.submitLog.status === 'FAIL') {
        item.state = 'failed';
        item.reason = result.submitLog.failReason ?? '后端校验未通过';
        failed += 1;
      } else {
        item.state = 'passed';
        passed += 1;
      }
    } catch {
      // 接口层已弹过错误提示，这里只标记该项失败
      item.state = 'failed';
      item.reason = item.reason || '请求失败';
      failed += 1;
    }
  }

  running.value = false;
  doneSummary.value = `导入结束：成功 ${passed} 个${failed > 0 ? `，失败 ${failed} 个` : ''}`;
  emit('finished');
}

/**
 * 生成一次提交的幂等键。
 *
 * <p>**每次提交一个唯一值**，与文件是否重复无关：同一份文件重新上传会拿到新的 fileId，
 * 对系统来说就是两份文件（`DefaultFileStorage.putFile` 每次 `IdWorker.getIdStr()`），
 * 新建档；幂等键只用来防"同一次请求被重复送达"（网络重试、并发重发）。
 *
 * <p>用时间戳 + 随机后缀，不用 `crypto.randomUUID()`：后者只在安全上下文（https/localhost）
 * 暴露，内网 http 部署下是 undefined，会把上传直接打断；幂等键只要求唯一、不要求不可预测。
 */
function newRequestId(): string {
  return `web-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

async function loadKnowledgeBases(): Promise<void> {
  kbLoading.value = true;
  try {
    const page = await getKnowledgeBasePage({ current: 1, size: KB_FETCH_SIZE });
    kbs.value = page.records;
  } catch {
    kbs.value = [];
  } finally {
    kbLoading.value = false;
  }
}

function onClosed(): void {
  items.value = [];
  doneSummary.value = '';
  pickedKbId.value = props.presetKbId ?? '';
}

// 每次打开都重新拉知识库列表：期间可能新建或被停用
watch(visible, (open) => {
  if (open) {
    items.value = [];
    doneSummary.value = '';
    kbKeyword.value = '';
    pickedKbId.value = props.presetKbId ?? '';
    void loadKnowledgeBases();
  }
});
</script>

<style scoped lang="css">
/* 左右两栏：左固定选库，右放文件 */
.import-cols {
  display: grid;
  gap: 16px;
  grid-template-columns: 250px 1fr;
  height: 384px;
}

/* ---- 左栏：知识库 ---- */
.import-kb-col {
  display: flex;
  flex-direction: column;
  min-height: 0;
  border: 1px solid var(--kb-line);
  border-radius: 12px;
  overflow: hidden;
}

.import-kb-head {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  border-bottom: 1px solid var(--kb-line);
}

.import-kb-title {
  font-size: 12px;
  font-weight: 650;
}

.import-kb-count {
  color: var(--kb-text-3);
  font-size: 11px;
}

.import-kb-search {
  flex: none;
  padding: 8px;
}

.import-kb-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 0 6px 6px;
}

.import-kb-item {
  display: flex;
  gap: 9px;
  align-items: flex-start;
  width: 100%;
  padding: 9px 10px;
  border: 1px solid transparent;
  border-radius: 9px;
  background: none;
  color: inherit;
  font-family: 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif;
  text-align: left;
  cursor: pointer;
}

/* 停用库：列出但禁选。
   放在 hover 之前：:disabled 选择器优先级更低，顺序反了会触发 no-descending-specificity */
.import-kb-item:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}

.import-kb-item:hover:not(:disabled) {
  background: rgb(255 255 255 / 4%);
}

.import-kb-item.is-on {
  border-color: rgb(52 211 153 / 40%);
  background: var(--kb-tint);
}

.import-kb-dot {
  width: 7px;
  height: 7px;
  flex: none;
  margin-top: 5px;
  border-radius: 50%;
  background: var(--kb-ok);
}

.import-kb-dot.is-off {
  background: var(--kb-text-3);
}

.import-kb-main {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
}

.import-kb-name {
  overflow: hidden;
  font-size: 12px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.import-kb-meta {
  display: flex;
  gap: 5px;
  align-items: center;
  margin-top: 3px;
  color: var(--kb-text-3);
  font-size: 10px;
}

.import-kb-empty {
  padding: 24px 0;
  color: var(--kb-text-3);
  font-size: 12px;
  text-align: center;
}

/* ---- 右栏：文件 ---- */
.import-file-col {
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.import-target {
  display: flex;
  flex: none;
  gap: 8px;
  align-items: center;
  padding: 9px 12px;
  border: 1px solid var(--kb-line);
  border-radius: 10px;
  background: rgb(255 255 255 / 3%);
  font-size: 12px;
}

.import-target-label {
  color: var(--kb-text-3);
  font-size: 11px;
}

.import-target-name {
  overflow: hidden;
  color: var(--kb-primary);
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.import-target-none {
  color: var(--kb-text-3);
}

.import-drop {
  display: flex;
  flex: none;
  flex-direction: column;
  gap: 5px;
  align-items: center;
  justify-content: center;
  height: 112px;
  margin-top: 12px;
  border: 1px dashed var(--kb-line-strong);
  border-radius: 12px;
  cursor: pointer;
  transition:
    border-color 0.15s,
    background 0.15s;
}

.import-drop:hover:not(.is-disabled),
.import-drop.is-over {
  border-color: var(--kb-primary);
  background: var(--kb-tint);
}

.import-drop.is-disabled {
  cursor: not-allowed;
  opacity: 0.5;
}

.import-drop-icon {
  color: var(--kb-primary);
  font-size: 20px;
}

.import-drop-text {
  color: var(--kb-text-2);
  font-size: 13px;
}

.import-drop-hint {
  color: var(--kb-text-3);
  font-size: 11px;
}

/* 原生 file input 只做触发用，不显示 */
.import-file-input {
  display: none;
}

.import-list {
  flex: 1;
  min-height: 0;
  margin-top: 12px;
  overflow-y: auto;
  border: 1px solid var(--kb-line);
  border-radius: 10px;
}

.import-item {
  display: flex;
  gap: 10px;
  align-items: center;
  padding: 9px 12px;
}

.import-item + .import-item {
  border-top: 1px solid var(--kb-line);
}

.import-item-dot {
  width: 6px;
  height: 6px;
  flex: none;
  border-radius: 50%;
  background: var(--kb-text-3);
}

.state-uploading,
.state-submitting {
  background: var(--kb-primary);
}

.state-passed {
  background: var(--kb-ok);
}

.state-failed {
  background: var(--kb-danger);
}

.import-item-main {
  min-width: 0;
  flex: 1;
}

.import-item-name {
  overflow: hidden;
  color: var(--kb-text-1);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.import-item-meta {
  margin-top: 2px;
  color: var(--kb-text-3);
  font-size: 11px;
}

.import-item-meta.is-err {
  color: var(--kb-danger);
}

.import-item-size {
  flex: none;
  color: var(--kb-text-3);
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
  font-size: 11px;
}

.import-item-remove {
  flex: none;
  padding: 0 4px;
  border: none;
  background: none;
  color: var(--kb-text-3);
  font-family: 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif;
  font-size: 14px;
  cursor: pointer;
}

.import-item-remove:hover {
  color: var(--kb-danger);
}

.import-summary {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-top: 14px;
  padding: 10px 12px;
  border: 1px solid rgb(52 211 153 / 30%);
  border-radius: 10px;
  background: var(--kb-tint);
  color: var(--kb-text-1);
  font-size: 12px;
}

.import-summary-next {
  color: var(--kb-text-3);
  font-size: 11px;
}
</style>
