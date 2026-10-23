<template>
  <div class="docx-preview">
    <!-- 工具条：缩放（Word 是流式排版，没有页码与翻页） -->
    <div class="docx-preview-bar">
      <span class="docx-preview-info">Word 原文</span>
      <span class="docx-preview-divider"></span>
      <button
        class="docx-preview-btn"
        type="button"
        :disabled="loading"
        title="缩小"
        @click="setZoom(zoom - ZOOM_STEP)"
      >
        －
      </button>
      <span class="docx-preview-info">{{ Math.round(zoom * 100) }}%</span>
      <button
        class="docx-preview-btn"
        type="button"
        :disabled="loading"
        title="放大"
        @click="setZoom(zoom + ZOOM_STEP)"
      >
        ＋
      </button>
    </div>

    <p v-if="loading" class="docx-preview-hint">原文件渲染中…</p>
    <p v-else-if="error" class="docx-preview-hint docx-preview-hint-bad">{{ error }}</p>

    <!-- 渲染区：docx-preview 把文档写进这里；只有这一层滚动 -->
    <div class="docx-preview-scroll">
      <div ref="host" class="docx-preview-host" :style="hostStyle" @click="onHostClick"></div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, shallowRef, watch } from 'vue';
import type * as DocxNs from 'docx-preview';

/** 原文预览里的一个锚点（Word 用段落序号 + 样式名定位） */
export interface DocxAnchor {
  /** 元素标识（与右栏元素行同键） */
  key: string;
  /** 元素类型（PARAGRAPH / TABLE 等，决定找 p 还是 table） */
  type: string;
  /** 段落序号（DOCX 元素 id 里的编号，0 基） */
  paragraphIndex: number;
  /** 段落样式名（w:pStyle 的 val） */
  style: string;
}

const props = defineProps<{
  /** 原文件字节（页面已取回；为空表示还没拿到） */
  blob: Blob | null;
  /** 可用于定位的元素锚点 */
  anchors: DocxAnchor[];
  /** 当前选中的锚点键 */
  activeKey: string;
}>();

const emit = defineEmits<{
  /** 在原文上点选命中的元素（未命中为 null） */
  pick: [key: string | null];
  /** 文档渲染完成 */
  loaded: [];
  /** 文档渲染失败 */
  failed: [message: string];
}>();

const MIN_ZOOM = 0.5;
const MAX_ZOOM = 2;
const ZOOM_STEP = 0.1;

/**
 * docx-preview 按需加载：Word 预览才需要它，打进首屏 chunk 会白白拖慢链图页。
 * 模块带缓存，第二次打开直接复用。
 */
type DocxModule = typeof DocxNs;

let docxModule: DocxModule | null = null;

async function loadDocx(): Promise<DocxModule> {
  docxModule ??= await import('docx-preview');
  return docxModule;
}

const host = ref<HTMLElement | null>(null);
/** 渲染产生的 DOM 不参与响应式：只关心"渲染完了没" */
const rendered = shallowRef(false);
const loading = ref(false);
const error = ref('');
const zoom = ref(1);

const hostStyle = computed(() => ({ transform: `scale(${zoom.value})` }));

function setZoom(next: number): void {
  zoom.value = Math.min(Math.max(next, MIN_ZOOM), MAX_ZOOM);
}

/**
 * 找到某元素对应的 DOM 节点：段落按序号、表格按表格序号（都来自元素 id）。
 *
 * <p>先用样式名收窄候选（docx-preview 把 `w:pStyle` 的 val 写成类名），
 * 收窄不到再退回纯序号匹配 —— 解析产物里的序号只统计非空段落，而渲染结果里空段落也在，
 * 序号可能因此错位。
 */
function nodeOf(anchor: DocxAnchor): HTMLElement | null {
  const root = host.value;
  if (root === null) {
    return null;
  }
  const isTable = anchor.type === 'TABLE';
  const selector = isTable ? 'table' : 'p';
  const all = Array.from(root.querySelectorAll<HTMLElement>(selector));
  const byStyle =
    anchor.style === '' ? [] : all.filter((el) => Array.from(el.classList).includes(anchor.style));
  const pool = byStyle.length > 0 ? byStyle : all;
  const node = pool[anchor.paragraphIndex] ?? null;
  return node;
}

/** 清掉上一轮的高亮（.docx-preview-hit 的样式在全局 chain-graph.css） */
function clearMarks(): void {
  host.value?.querySelectorAll('.docx-preview-hit').forEach((el) => {
    el.classList.remove('docx-preview-hit');
  });
}

/** 把某个元素滚到可见并高亮（段落底色） */
async function revealAnchor(key: string): Promise<void> {
  const anchor = props.anchors.find((item) => item.key === key);
  if (anchor === undefined) {
    return;
  }
  await nextTick();
  clearMarks();
  const node = nodeOf(anchor);
  if (node === null) {
    return;
  }
  node.classList.add('docx-preview-hit');
  node.scrollIntoView({ block: 'center', behavior: 'smooth' });
}

/**
 * 原文上点选：命中被点中的段落或表格。
 *
 * <p>从事件目标往上找最近的 p/table，再按它在同类块里的序号反查元素键。
 */
function onHostClick(event: MouseEvent): void {
  const root = host.value;
  const target = event.target;
  if (root === null || !(target instanceof HTMLElement)) {
    return;
  }
  const node = target.closest('p, table');
  if (!(node instanceof HTMLElement)) {
    emit('pick', null);
    return;
  }
  const blocks = Array.from(root.querySelectorAll<HTMLElement>('p, table'));
  if (!blocks.includes(node)) {
    emit('pick', null);
    return;
  }
  const isTable = node.tagName === 'TABLE';
  const sameKind = blocks.filter((el) => (el.tagName === 'TABLE') === isTable);
  const ordinal = sameKind.indexOf(node);
  const hit = props.anchors.find(
    (anchor) => (anchor.type === 'TABLE') === isTable && anchor.paragraphIndex === ordinal,
  );
  emit('pick', hit?.key ?? null);
}

function destroyHost(): void {
  rendered.value = false;
  error.value = '';
  const root = host.value;
  if (root !== null) {
    root.innerHTML = '';
  }
}

/**
 * 渲染原文件。
 *
 * <p>同时盯 `blob` 与 `host`：组件挂载时 `host` 还是 null（模板里它属于会被 `v-if` 摘掉的分支），
 * 只盯 blob 会在挂载那一次拿到 null 而提前返回、之后再也不会触发。
 */
watch(
  [() => props.blob, host],
  async ([blob, root]) => {
    if (blob === null) {
      destroyHost();
      loading.value = false;
      return;
    }
    if (root === null) {
      // host 还没挂上：等它出现会再触发一次
      return;
    }
    destroyHost();
    loading.value = true;
    try {
      const buffer = await blob.arrayBuffer();
      const mod = await loadDocx();
      await mod.renderAsync(buffer, root, root, {
        // 按内容宽度排版，横向交给外层的缩放进出
        inWrapper: true,
        breakPages: true,
        ignoreWidth: false,
        ignoreHeight: false,
      });
      rendered.value = true;
      emit('loaded');
    } catch (err) {
      const reason = err instanceof Error ? err.message : '未知原因';
      error.value = `Word 解析失败：${reason}`;
      emit('failed', error.value);
    } finally {
      loading.value = false;
    }
  },
  { immediate: true },
);

onBeforeUnmount(() => {
  destroyHost();
});

defineExpose({ revealAnchor });
</script>

<style scoped lang="css">
.docx-preview {
  display: flex;
  min-height: 0;
  height: 100%;
  flex-direction: column;
}

.docx-preview-bar {
  display: flex;
  flex: none;
  align-items: center;
  gap: 4px;
  padding: 6px 8px;
  border-bottom: 1px solid var(--kb-line);
  background: var(--kb-bg-2);
}

.docx-preview-btn {
  min-width: 24px;
  padding: 3px 6px;
  border: 1px solid var(--kb-line);
  border-radius: 4px;
  background: transparent;
  color: var(--kb-text-2);
  font-family: 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif;
  font-size: 13px;
  line-height: 1;
  cursor: pointer;
}

.docx-preview-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.docx-preview-btn:hover:enabled {
  border-color: var(--kb-primary);
  color: var(--kb-primary);
}

.docx-preview-info {
  color: var(--kb-text-3);
  font-family: 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif;
  font-size: 12px;
}

.docx-preview-divider {
  flex: 1;
}

.docx-preview-hint {
  margin: 8px;
  color: var(--kb-text-3);
  font-family: 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif;
  font-size: 12px;
}

.docx-preview-hint-bad {
  color: var(--kb-warn);
}

/* 渲染区：本组件唯一的滚动容器 */
.docx-preview-scroll {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 10px;
  background: var(--kb-bg-0);
}

.docx-preview-host {
  transform-origin: top left;
  color: var(--kb-preview-text);
  cursor: text;
}
</style>
