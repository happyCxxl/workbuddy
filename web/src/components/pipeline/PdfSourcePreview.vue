<template>
  <div class="pdf-preview">
    <!-- 工具条：页码 / 翻页 / 缩放 -->
    <div class="pdf-preview-bar">
      <button
        class="pdf-preview-btn"
        type="button"
        :disabled="currentPage <= 1 || loading"
        @click="goToPage(currentPage - 1)"
      >
        上一页
      </button>
      <span class="pdf-preview-info"
        >第
        <input
          v-model.number="pageInput"
          class="pdf-preview-page-input"
          type="number"
          min="1"
          :max="pageTotal || 1"
          :disabled="pageTotal === 0 || loading"
          @keyup.enter="jumpToPage"
        />
        / {{ pageTotal || '—' }} 页</span
      >
      <button
        class="pdf-preview-btn"
        type="button"
        :disabled="pageTotal === 0 || loading"
        @click="jumpToPage"
      >
        跳转
      </button>
      <button
        class="pdf-preview-btn"
        type="button"
        :disabled="pageTotal === 0 || currentPage >= pageTotal || loading"
        @click="goToPage(currentPage + 1)"
      >
        下一页
      </button>
      <span class="pdf-preview-divider"></span>
      <button
        class="pdf-preview-btn pdf-preview-icon"
        type="button"
        :class="{ 'is-on': fitWidth }"
        :title="fitWidth ? '适应宽度：按栏宽铺满（点此回到 100%）' : '适应宽度：按栏宽铺满'"
        @click="toggleFitWidth"
      >
        ↔
      </button>
      <button
        class="pdf-preview-btn pdf-preview-icon"
        type="button"
        :disabled="fitWidth || zoom <= MIN_ZOOM"
        title="缩小"
        @click="setZoom(zoom - ZOOM_STEP)"
      >
        －
      </button>
      <span class="pdf-preview-info">{{ Math.round(zoom * 100) }}%</span>
      <button
        class="pdf-preview-btn pdf-preview-icon"
        type="button"
        :disabled="fitWidth || zoom >= MAX_ZOOM"
        title="放大"
        @click="setZoom(zoom + ZOOM_STEP)"
      >
        ＋
      </button>
    </div>

    <p v-if="loading" class="pdf-preview-hint">原文件加载中…</p>
    <p v-else-if="error" class="pdf-preview-hint pdf-preview-hint-bad">{{ error }}</p>

    <!-- 页容器：按页懒渲染，只画已进入视野的页 -->
    <div ref="scroller" class="pdf-preview-scroll" @scroll="onScroll">
      <div class="pdf-preview-stack" :style="stackStyle">
        <div
          v-for="pageNo in renderPages"
          :key="pageNo"
          class="pdf-preview-page"
          :class="{ 'is-ready': pageReady(pageNo) }"
          :style="pageStyle(pageNo)"
        >
          <canvas :ref="(el) => setCanvasRef(pageNo, el)" class="pdf-preview-canvas"></canvas>
          <div class="pdf-preview-overlay" @click="onPageClick(pageNo, $event)">
            <span
              v-for="box in activeBoxesOfPage(pageNo)"
              :key="box.key"
              class="pdf-preview-box"
              :class="{ 'is-active': box.key === activeKey }"
              :style="rectStyle(box.bbox)"
              :title="box.title"
            ></span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue';
// 运行时从构建产物取（包根入口不带 worker 路径），类型从包根取
// @ts-expect-error 子路径没有类型声明，类型走下面的包根导入
import { GlobalWorkerOptions, getDocument } from 'pdfjs-dist/build/pdf.mjs';
import type {
  PDFDocumentLoadingTask,
  PDFDocumentProxy,
  PDFPageProxy,
  RenderTask,
} from 'pdfjs-dist';
// worker 用 Vite 的 worker 打包：pdf.js 的 worker 要作为完整单元加载
import workerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?worker&url';
import standardFontUrl from 'pdfjs-dist/standard_fonts/LiberationSans-Regular.ttf?url';

import type { ElementBBox } from '@/types/pipeline';

GlobalWorkerOptions.workerSrc = workerUrl;

/**
 * 标准字体目录：PDF 里通常只写字体名（如 Helvetica），实际字形要另外取，
 * 缺了会渲染不出文字。目录由资源 URL 推出，开发与打包后指向同一处。
 */
const STANDARD_FONT_DIR = standardFontUrl.slice(0, standardFontUrl.lastIndexOf('/') + 1);

/** 原文预览里的一个高亮框（坐标同解析产物的 bbox：单位点、左上角原点） */
export interface PreviewHighlight {
  /** 元素标识（与右栏元素行同键） */
  key: string;
  /** 所属文档页 */
  page: number;
  bbox: ElementBBox;
  /** 悬停说明（类型 + 摘录） */
  title: string;
}

const props = defineProps<{
  /** 原文件字节（页面已取回；为空表示还没拿到） */
  blob: Blob | null;
  /** 当前要高亮的框（空表示不高亮任何元素） */
  highlights: PreviewHighlight[];
  /** 当前选中的高亮键 */
  activeKey: string;
}>();

const emit = defineEmits<{
  /** 当前页变化（翻页 / 滚动都可能触发） */
  pageChange: [page: number];
  /** 在原文上点选命中的元素（未命中为 null） */
  pick: [key: string | null];
  /** 文档加载完成 */
  loaded: [pageTotal: number];
  /** 文档加载失败 */
  failed: [message: string];
}>();

/** 对外暴露的能力（父组件做双栏联动时调用） */
export interface PdfPreviewApi {
  /** 跳到某页；silent 为 true 时不发 pageChange（由调用方自己接管当前页） */
  goToPage: (page: number, options?: { silent?: boolean }) => Promise<void>;
  /** 把某个高亮滚到可见 */
  revealHighlight: (key: string) => Promise<void>;
}

const MIN_ZOOM = 0.5;
const MAX_ZOOM = 3;
const ZOOM_STEP = 0.25;

/** 单页渲染的像素倍率：画布按 2 倍画、按 1 倍显示，放大时才不糊 */
const RENDER_RATIO = 2;

/** 页面之间的垂直间距（px，不属于页面坐标系） */
const PAGE_GAP = 12;

const scroller = ref<HTMLElement | null>(null);
/**
 * 当前文档与加载任务：**必须用 shallowRef**。
 *
 * <p>pdf.js 的对象是带类私有字段的实例，`ref` 会把它包成响应式代理，
 * 之后读私有字段会报 "Cannot read private member #pagesNumber"。
 * 这里只关心"对象换没换"，不需要深层响应式。
 */
const loadingTask = shallowRef<PDFDocumentLoadingTask | null>(null);
const doc = shallowRef<PDFDocumentProxy | null>(null);
const pageTotal = ref(0);
const currentPage = ref(1);
/** 工具条页码输入：显示当前页，回车/跳转后校验范围 */
const pageInput = ref(1);
const zoom = ref(1);

// 翻页/滚动后同步输入框显示；输入框只在回车或点“跳转”时反推当前页
watch(currentPage, (page) => {
  pageInput.value = page;
});
/**
 * 适应宽度档：缩放跟着可用宽度走（分隔条拖宽后内容跟着放大，而不是只多出留白）。
 *
 * <p>开启时"缩小/放大"按钮禁用 —— 手改缩放会与自动跟随互相打架；
 * 点一次「适应宽度」即回到手动 100%。
 */
const fitWidth = ref(false);
const loading = ref(false);
const error = ref('');

/** 每页的显示尺寸（未缩放时的 CSS px，由该页 viewport(1) 决定）；未就绪的页不在表里 */
const pageSizes = ref<Partial<Record<number, { width: number; height: number }>>>({});
/** 已渲染的页号 */
const rendered = ref(new Set<number>());
/** 正在渲染中的页号（重复滚动不重复触发） */
const rendering = ref(new Set<number>());

const canvasRefs = new Map<number, HTMLCanvasElement>();

const renderPages = computed(() => Array.from({ length: pageTotal.value }, (_, i) => i + 1));

/** 容器与高亮层共用页面尺寸；未就绪时高度为 0（不占位、不显示） */
function pageStyle(pageNo: number): Record<string, string> {
  const size = pageSizes.value[pageNo];
  return {
    width: size === undefined ? '0px' : `${size.width}px`,
    height: size === undefined ? '0px' : `${size.height}px`,
    marginBottom: `${PAGE_GAP}px`,
  };
}

/** 一个元素的显示矩形：逐行框优先，旧产物/表格退回整块 bbox */
function rectStyle(rect: ElementBBox): Record<string, string> {
  return {
    left: `${rect.x}px`,
    top: `${rect.y}px`,
    width: `${rect.width}px`,
    height: `${rect.height}px`,
  };
}

const stackStyle = computed(() => ({
  transform: `scale(${zoom.value})`,
}));

/**
 * 该页是否已拿到显示尺寸。
 *
 * <p>未就绪时容器高度为 0、内容不可见：canvas 没设宽高会先显示成 300×150 的默认大小，
 * 尺寸到位后再跳一下。
 */
function pageReady(pageNo: number): boolean {
  return pageSizes.value[pageNo] !== undefined;
}

function setCanvasRef(pageNo: number, el: unknown): void {
  if (el instanceof HTMLCanvasElement) {
    canvasRefs.set(pageNo, el);
  } else {
    canvasRefs.delete(pageNo);
  }
}

/** 该页要画的高亮框（全部，供点击命中使用） */
function boxesOfPage(pageNo: number): PreviewHighlight[] {
  return props.highlights.filter((item) => item.page === pageNo);
}

/** 只渲染当前选中元素的框：未点击时不铺满绿色默认框，点击后只出现一个黄色框 */
function activeBoxesOfPage(pageNo: number): PreviewHighlight[] {
  if (props.activeKey === '') {
    return [];
  }
  return boxesOfPage(pageNo).filter((item) => item.key === props.activeKey);
}

/** 某页的总高（未就绪按 0 计） */
function pageHeight(pageNo: number): number {
  const size = pageSizes.value[pageNo];
  return size === undefined ? 0 : size.height;
}

/** 某页之前所有页的累计高度（含页间距） */
function offsetBefore(pageNo: number): number {
  let offset = 0;
  for (const page of renderPages.value) {
    if (page === pageNo) {
      break;
    }
    offset += pageHeight(page) + PAGE_GAP;
  }
  return offset;
}

/** 取页面显示尺寸：按 viewport(1) 记下宽高，画布与高亮层共用同一坐标系 */
async function ensurePageSize(pageNo: number): Promise<{ width: number; height: number } | null> {
  const size = pageSizes.value[pageNo];
  if (size !== undefined) {
    return size;
  }
  const pdfDocument = doc.value;
  if (pdfDocument === null) {
    return null;
  }
  const pdfPage = await pdfDocument.getPage(pageNo);
  const viewport = pdfPage.getViewport({ scale: 1 });
  const next = { width: Math.round(viewport.width), height: Math.round(viewport.height) };
  pageSizes.value = { ...pageSizes.value, [pageNo]: next };
  return next;
}

/**
 * 渲染单页：画布就绪后按 2 倍绘制，只画一次。
 *
 * <p>画布按 RENDER_RATIO 倍设置像素、按 1 倍设置 CSS 尺寸，
 * 因此 bbox（PDF 点）可以直接当 CSS px 用，缩放交给外层 transform。
 */
async function renderPage(pageNo: number): Promise<void> {
  const pdfDocument = doc.value;
  if (pdfDocument === null || rendered.value.has(pageNo) || rendering.value.has(pageNo)) {
    return;
  }
  const canvas = canvasRefs.get(pageNo);
  if (canvas === undefined) {
    return;
  }
  rendering.value = new Set(rendering.value).add(pageNo);
  try {
    const size = await ensurePageSize(pageNo);
    if (size === null) {
      return;
    }
    const pdfPage: PDFPageProxy = await pdfDocument.getPage(pageNo);
    const viewport = pdfPage.getViewport({ scale: RENDER_RATIO });
    canvas.width = Math.round(viewport.width);
    canvas.height = Math.round(viewport.height);
    canvas.style.width = `${size.width}px`;
    canvas.style.height = `${size.height}px`;
    const context = canvas.getContext('2d');
    if (context === null) {
      return;
    }
    const task: RenderTask = pdfPage.render({ canvas, canvasContext: context, viewport });
    await task.promise;
    rendered.value = new Set(rendered.value).add(pageNo);
  } catch {
    // 单页渲染失败不影响其它页与右栏：整份加载失败才由 failed 上报
  } finally {
    const next = new Set(rendering.value);
    next.delete(pageNo);
    rendering.value = next;
  }
}

/** 当前视野内的页：只渲染这些（大文档不把整份画出来） */
function visiblePageNumbers(): number[] {
  const element = scroller.value;
  if (element === null) {
    return [1];
  }
  const top = element.scrollTop / zoom.value;
  const bottom = (element.scrollTop + element.clientHeight) / zoom.value;
  let offset = 0;
  const hits: number[] = [];
  for (const pageNo of renderPages.value) {
    const pageTop = offset;
    const pageBottom = offset + pageHeight(pageNo);
    if (pageBottom >= top && pageTop <= bottom) {
      hits.push(pageNo);
    }
    offset = pageBottom + PAGE_GAP;
    if (pageTop > bottom && hits.length > 0) {
      break;
    }
  }
  return hits.length > 0 ? hits : [1];
}

async function renderVisible(): Promise<void> {
  await nextTick();
  for (const pageNo of visiblePageNumbers()) {
    void renderPage(pageNo);
  }
}

/** 滚动时同步当前页并补渲染新进入视野的页 */
function onScroll(): void {
  const hits = visiblePageNumbers();
  const first = hits[0];
  if (first !== currentPage.value) {
    currentPage.value = first;
    emit('pageChange', first);
  }
  void renderVisible();
}

/**
 * 跳到某一页：滚动到该页顶部，并把当前页立即切过去。
 *
 * <p>滚动会再触发一次 onScroll，那里按同一口径算出的首页就是目标页。
 */
async function goToPage(pageNo: number, options: { silent?: boolean } = {}): Promise<void> {
  const target = Math.min(Math.max(Math.trunc(pageNo), 1), pageTotal.value || 1);
  const element = scroller.value;
  await ensurePageSize(target);
  await nextTick();
  if (element === null) {
    return;
  }
  element.scrollTop = offsetBefore(target) * zoom.value;
  currentPage.value = target;
  if (options.silent !== true) {
    emit('pageChange', target);
  }
  await renderVisible();
}

/** 页码输入跳转：夹到 1..pageTotal，非法输入回落到当前页 */
function jumpToPage(): void {
  const target = Math.trunc(Number(pageInput.value));
  if (!Number.isFinite(target) || pageTotal.value === 0) {
    pageInput.value = currentPage.value;
    return;
  }
  const clamped = Math.min(Math.max(target, 1), pageTotal.value);
  pageInput.value = clamped;
  void goToPage(clamped);
}

/** 缩放：按前后比例修正滚动位置，视觉上停在原处 */
function setZoom(next: number): void {
  const clamped = Math.min(Math.max(next, MIN_ZOOM), MAX_ZOOM);
  if (clamped === zoom.value) {
    return;
  }
  const element = scroller.value;
  const ratio = clamped / zoom.value;
  const before = element === null ? 0 : element.scrollTop;
  zoom.value = clamped;
  void nextTick(() => {
    if (element !== null) {
      element.scrollTop = before * ratio;
    }
    void renderVisible();
  });
}

/** 当前页在 1 倍下的宽度；取不到时返回 0 */
function pageBaseWidth(): number {
  const first = pageSizes.value[1];
  return first === undefined ? 0 : first.width;
}

/**
 * 按可用宽度算"适应宽度"的缩放比。
 *
 * <p>减去滚动区左右内边距（左右各 10px）与一点余量，避免刚好贴边出现横向滚动条。
 */
function fitZoom(): number {
  const element = scroller.value;
  const base = pageBaseWidth();
  if (element === null || base <= 0) {
    return zoom.value;
  }
  const available = element.clientWidth - 20;
  return Math.min(Math.max(available / base, MIN_ZOOM), MAX_ZOOM);
}

/** 缩放值变化但不动滚动位置（跟随栏宽时用：页宽本来就变了，再补偿会漂） */
function applyZoomWithoutScroll(next: number): void {
  const clamped = Math.min(Math.max(next, MIN_ZOOM), MAX_ZOOM);
  if (Math.abs(clamped - zoom.value) < 0.001) {
    return;
  }
  zoom.value = clamped;
  void nextTick(() => {
    void renderVisible();
  });
}

/** 适应宽度 / 手动 100% 互切 */
function toggleFitWidth(): void {
  fitWidth.value = !fitWidth.value;
  if (fitWidth.value) {
    applyZoomWithoutScroll(fitZoom());
    return;
  }
  setZoom(1);
}

/**
 * 栏宽变化（拖分隔条、窗口缩放）时，适应宽度档要跟着重算。
 *
 * <p>监听滚动区宽度：分隔条拖动只改这一处宽度，window.resize 收不到。
 */
function onFitResize(): void {
  if (!fitWidth.value) {
    return;
  }
  applyZoomWithoutScroll(fitZoom());
}

/**
 * 页面上点击：按坐标命中最近的元素框。
 *
 * <p>判定用**未缩放**的页面坐标：先扣掉高亮层在视口里的位置，再除以缩放比；
 * 命中的多个框里取面积最小的那个（嵌套时优先最具体的一个）。
 */
function onPageClick(pageNo: number, event: MouseEvent): void {
  const overlay = event.currentTarget;
  if (!(overlay instanceof HTMLElement)) {
    return;
  }
  const rect = overlay.getBoundingClientRect();
  const x = (event.clientX - rect.left) / zoom.value;
  const y = (event.clientY - rect.top) / zoom.value;
  const candidates = boxesOfPage(pageNo).filter(
    (box) =>
      x >= box.bbox.x &&
      x <= box.bbox.x + box.bbox.width &&
      y >= box.bbox.y &&
      y <= box.bbox.y + box.bbox.height,
  );
  if (candidates.length === 0) {
    emit('pick', null);
    return;
  }
  candidates.sort((a, b) => a.bbox.width * a.bbox.height - b.bbox.width * b.bbox.height);
  emit('pick', candidates[0].key);
}

/** 把某个高亮滚到可见（联动"点元素 → 原文定位"） */
async function revealHighlight(key: string): Promise<void> {
  const box = props.highlights.find((item) => item.key === key);
  if (box === undefined) {
    return;
  }
  if (box.page !== currentPage.value) {
    await goToPage(box.page);
  }
  const element = scroller.value;
  if (element === null) {
    return;
  }
  const pageTop = offsetBefore(box.page) * zoom.value;
  const target = (offsetBefore(box.page) + box.bbox.y) * zoom.value - element.clientHeight / 3;
  // 首个元素靠近页顶时，减三分之一视口会把滚动位置推到上一页；
  // 定位最低只能停在目标页页顶，避免 onScroll 把当前页判回上一页并触发右栏重取
  element.scrollTo({ top: Math.max(pageTop, target, 0), behavior: 'smooth' });
}

function destroyDoc(): void {
  const task = loadingTask.value;
  loadingTask.value = null;
  doc.value = null;
  canvasRefs.clear();
  pageSizes.value = {};
  rendered.value = new Set<number>();
  rendering.value = new Set<number>();
  pageTotal.value = 0;
  if (task !== null) {
    // 释放 worker 与已解析的页数据；失败不阻断下一次加载
    void task.destroy().catch(() => undefined);
  }
}

/** 载入原文件：每次 blob 变化重建文档（换文件 / 换运行都要重来） */
watch(
  () => props.blob,
  async (blob) => {
    destroyDoc();
    error.value = '';
    if (blob === null) {
      loading.value = false;
      return;
    }
    loading.value = true;
    try {
      const bytes = new Uint8Array(await blob.arrayBuffer());
      const task = getDocument({ data: bytes, standardFontDataUrl: STANDARD_FONT_DIR });
      loadingTask.value = task;
      const pdfDocument = await task.promise;
      doc.value = pdfDocument;
      pageTotal.value = pdfDocument.numPages;
      currentPage.value = 1;
      await ensurePageSize(1);
      await renderVisible();
      emit('loaded', pdfDocument.numPages);
    } catch (err) {
      // 带上 pdf.js 给的原因：加密、损坏、非 PDF 的处理方式不同
      const reason = err instanceof Error ? err.message : '未知原因';
      error.value = `PDF 解析失败：${reason}`;
      emit('failed', error.value);
    } finally {
      loading.value = false;
    }
  },
  { immediate: true },
);

// 高亮集合变化：把新进入视野的页补渲染，保证框有地方画
watch(
  () => props.highlights,
  () => {
    void renderVisible();
  },
);

/** 监听滚动区宽度，供"适应宽度"跟随分隔条拖动 */
let fitObserver: ResizeObserver | null = null;

onMounted(() => {
  const element = scroller.value;
  if (element !== null && typeof ResizeObserver === 'function') {
    fitObserver = new ResizeObserver(() => {
      onFitResize();
    });
    fitObserver.observe(element);
  }
});

onBeforeUnmount(() => {
  fitObserver?.disconnect();
  fitObserver = null;
  destroyDoc();
});

defineExpose({ goToPage, revealHighlight, setZoom });
</script>

<style scoped lang="css">
.pdf-preview {
  display: flex;
  min-height: 0;
  height: 100%;
  flex-direction: column;
}

/* 工具条：页码 / 翻页 / 缩放 */
.pdf-preview-bar {
  display: flex;
  flex: none;
  align-items: center;
  gap: 4px;
  padding: 6px 8px;
  border-bottom: 1px solid var(--kb-line);
  background: var(--kb-bg-2);
}

.pdf-preview-btn {
  padding: 3px 8px;
  border: 1px solid var(--kb-line);
  border-radius: 4px;
  background: transparent;
  color: var(--kb-text-2);
  font-family: 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif;
  font-size: 12px;
  cursor: pointer;
}

.pdf-preview-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

/* 当前按栏宽铺满（适应宽度档） */
.pdf-preview-btn.is-on {
  border-color: var(--kb-primary);
  background: var(--kb-tint);
  color: var(--kb-primary);
}

/* 图标按钮：栏被拖窄时工具条仍要放得下，故用单字符 */
.pdf-preview-icon {
  min-width: 24px;
  padding: 3px 6px;
  font-size: 13px;
  line-height: 1;
}

.pdf-preview-btn:hover:enabled {
  border-color: var(--kb-primary);
  color: var(--kb-primary);
}

.pdf-preview-info {
  min-width: 62px;
  color: var(--kb-text-3);
  font-family: 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif;
  font-size: 12px;
  text-align: center;
}

.pdf-preview-page-input {
  width: 44px;
  padding: 2px 4px;
  border: 1px solid var(--kb-line);
  border-radius: 4px;
  background: var(--kb-bg-1);
  color: var(--kb-text-1);
  font-family: 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif;
  font-size: 12px;
  text-align: center;
}

.pdf-preview-divider {
  flex: 1;
}

.pdf-preview-hint {
  margin: 8px;
  color: var(--kb-text-3);
  font-family: 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif;
  font-size: 12px;
}

.pdf-preview-hint-bad {
  color: var(--kb-warn);
}

/* 滚动区：页多时也只渲染视野内的那几页 */
.pdf-preview-scroll {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 10px;
  background: var(--kb-bg-0);
}

.pdf-preview-stack {
  display: flex;
  flex-direction: column;
  align-items: center;
  transform-origin: top center;
}

.pdf-preview-page {
  position: relative;
  flex: none;
  overflow: hidden;
  background: var(--kb-preview-bg);
  box-shadow: 0 2px 10px rgb(0 0 0 / 35%);
}

/* 尺寸未就绪的页：先不占位、不显示（画布默认 300×150，露出来会跳一下） */
.pdf-preview-page:not(.is-ready) {
  visibility: hidden;
}

.pdf-preview-canvas {
  display: block;
}

.pdf-preview-overlay {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  cursor: crosshair;
}

.pdf-preview-box {
  position: absolute;
  border: 2px solid var(--kb-warn);
  border-radius: 2px;
  background: color-mix(in srgb, var(--kb-warn) 26%, transparent);
  pointer-events: none;
}

.pdf-preview-box.is-active {
  border-color: var(--kb-warn);
  background: color-mix(in srgb, var(--kb-warn) 26%, transparent);
}
</style>
