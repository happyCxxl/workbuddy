<template>
  <div class="xlsx-preview">
    <!-- 工具条：Sheet 切换 -->
    <div class="xlsx-preview-bar">
      <button
        v-for="name in sheetNames"
        :key="name"
        class="xlsx-preview-tab"
        :class="{ 'is-on': name === activeSheet }"
        type="button"
        @click="switchSheet(name)"
      >
        {{ name }}
      </button>
      <span class="xlsx-preview-divider"></span>
      <span class="xlsx-preview-info">{{ cellRows.length }} 行 · {{ totalRows }} 行</span>
      <span v-if="truncated" class="xlsx-preview-hint">已截断</span>
    </div>

    <p v-if="loading" class="xlsx-preview-hint">原文件渲染中…</p>
    <p v-else-if="error" class="xlsx-preview-hint xlsx-preview-hint-bad">{{ error }}</p>

    <!-- 表格区：本组件唯一的滚动容器；只渲染前若干行，避免大表把 DOM 撑爆 -->
    <div ref="scroller" class="xlsx-preview-scroll" @click="onGridClick">
      <table v-if="cellRows.length > 0" class="xlsx-preview-grid">
        <tbody>
          <tr v-for="row in cellRows" :key="row.index">
            <th class="xlsx-preview-rownum">{{ row.index + 1 }}</th>
            <td
              v-for="cell in row.cells"
              :key="cell.col"
              class="xlsx-preview-cell"
              :class="{ 'is-hit': cell.key !== '' && cell.key === activeKey }"
              :data-key="cell.key"
              :title="cell.text"
            >
              {{ cell.text }}
            </td>
          </tr>
        </tbody>
      </table>
      <p v-else class="xlsx-preview-hint">该工作表没有可展示的内容</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, shallowRef, watch } from 'vue';
import type * as XlsxNs from 'xlsx';

/** 原文预览里的一个锚点（Excel 用工作表 + 行列区间定位） */
export interface XlsxAnchor {
  /** 元素标识（与右栏元素行同键） */
  key: string;
  /** 元素类型（TABLE / TABLE_CELL） */
  type: string;
  /** 工作表名 */
  sheetName: string;
  /** 起始行（0 基） */
  row: number;
  /** 起始列（0 基） */
  col: number;
  /** 行跨度（合并单元格） */
  rowSpan: number;
  /** 列跨度（合并单元格） */
  colSpan: number;
}

const props = defineProps<{
  /** 原文件字节（页面已取回；为空表示还没拿到） */
  blob: Blob | null;
  /** 可用于定位的元素锚点 */
  anchors: XlsxAnchor[];
  /** 当前选中的锚点键 */
  activeKey: string;
}>();

const emit = defineEmits<{
  /** 在原文上点选命中的元素（未命中为 null） */
  pick: [key: string | null];
  /** 工作簿解析完成 */
  loaded: [];
  /** 解析失败 */
  failed: [message: string];
}>();

/** 单表最多渲染的行数：大表全量进 DOM 会卡（超出部分提示已截断） */
const MAX_RENDER_ROWS = 200;
/** 列数上限：与行同理 */
const MAX_RENDER_COLS = 60;

/**
 * xlsx 按需加载：Excel 预览才需要它，打进首屏 chunk 会白白拖慢链图页。
 * 模块带缓存，第二次打开直接复用。
 */
type XlsxModule = typeof XlsxNs;

let xlsxModule: XlsxModule | null = null;

async function loadXlsx(): Promise<XlsxModule> {
  xlsxModule ??= await import('xlsx');
  return xlsxModule;
}

/** 取已加载的模块；未加载时抛错 */
function xlsx(): XlsxModule {
  if (xlsxModule === null) {
    throw new Error('xlsx 尚未加载');
  }
  return xlsxModule;
}

const scroller = ref<HTMLElement | null>(null);
const workbook = shallowRef<XlsxNs.WorkBook | null>(null);
const sheetNames = ref<string[]>([]);
const activeSheet = ref('');
const loading = ref(false);
const error = ref('');

/** 当前工作表的纯文本网格：行 × 列（缺失单元格补空串，保证列对齐） */
const grid = ref<string[][]>([]);
/** 该表实际总行数（未截断前） */
const totalRows = ref(0);

const truncated = computed(() => totalRows.value > grid.value.length);

/** 行号 + 单元格（单元格带上命中的元素键） */
const cellRows = computed(() => {
  const keyByCell = new Map<string, string>();
  for (const anchor of props.anchors) {
    if (anchor.sheetName !== activeSheet.value) {
      continue;
    }
    keyByCell.set(`${anchor.row},${anchor.col}`, anchor.key);
  }
  return grid.value.map((cells, index) => ({
    index,
    cells: cells.map((text, col) => ({
      col,
      text,
      key: keyByCell.get(`${index},${col}`) ?? '',
    })),
  }));
});

/** 切到某个工作表：重新铺网格（高亮键不变，故左栏不会因切表而乱） */
function switchSheet(name: string): void {
  const book = workbook.value;
  if (book === null || name === activeSheet.value) {
    return;
  }
  activeSheet.value = name;
  buildGrid(book, name);
}

/**
 * 把工作表读成纯文本网格（`header: 1` 给二维数组；空白单元格不回行）。
 *
 * <p>sheet_to_json 的类型签名写的是二维数组，但空单元格实际会给 undefined，
 * 故取值时仍按 unknown 逐个兜底（不信任签名）。
 */
function buildGrid(book: XlsxNs.WorkBook, name: string): void {
  const sheet: unknown = book.Sheets[name];
  if (sheet === undefined) {
    grid.value = [];
    totalRows.value = 0;
    return;
  }
  const rows = xlsx().utils.sheet_to_json<unknown[]>(sheet as never, {
    header: 1,
    blankrows: false,
    defval: '',
  });
  totalRows.value = rows.length;
  grid.value = rows.slice(0, MAX_RENDER_ROWS).map((row) => {
    const cells = Array.isArray(row) ? row.slice(0, MAX_RENDER_COLS) : [];
    return cells.map((cell) => (typeof cell === 'string' ? cell : String(cell ?? '')));
  });
}

/** 把某个元素滚到可见并高亮（单元格区域底色） */
async function revealAnchor(key: string): Promise<void> {
  const anchor = props.anchors.find((item) => item.key === key);
  if (anchor === undefined) {
    return;
  }
  // 目标可能在别的工作表：先切过去
  if (anchor.sheetName !== '' && anchor.sheetName !== activeSheet.value) {
    const book = workbook.value;
    if (book?.Sheets[anchor.sheetName] !== undefined) {
      activeSheet.value = anchor.sheetName;
      buildGrid(book, anchor.sheetName);
    }
  }
  await nextTick();
  const root = scroller.value;
  if (root === null) {
    return;
  }
  const cell = root.querySelector(`[data-key="${CSS.escape(key)}"]`);
  if (cell instanceof HTMLElement) {
    cell.scrollIntoView({ block: 'center', inline: 'center', behavior: 'smooth' });
  }
}

/**
 * 表格区点选：命中被点中的单元格或整行/整表区域。
 *
 * <p>优先用单元格自己的元素键；没有（空白格）就回落到"该表 TABLE 元素"，
 * 即点空白处仍能定位到这张表。
 */
function onGridClick(event: MouseEvent): void {
  const target = event.target;
  if (!(target instanceof HTMLElement)) {
    return;
  }
  const cell = target.closest('td');
  const key = cell instanceof HTMLElement ? (cell.dataset.key ?? '') : '';
  if (key !== '') {
    emit('pick', key);
    return;
  }
  const tableAnchor = props.anchors.find(
    (anchor) => anchor.type === 'TABLE' && anchor.sheetName === activeSheet.value,
  );
  emit('pick', tableAnchor?.key ?? null);
}

/** 解析原文件：blob 变化即重来 */
watch(
  () => props.blob,
  async (blob) => {
    workbook.value = null;
    sheetNames.value = [];
    activeSheet.value = '';
    grid.value = [];
    totalRows.value = 0;
    error.value = '';
    if (blob === null) {
      loading.value = false;
      return;
    }
    loading.value = true;
    try {
      const buffer = await blob.arrayBuffer();
      const mod = await loadXlsx();
      const book = mod.read(buffer, { type: 'array' });
      workbook.value = book;
      sheetNames.value = book.SheetNames;
      const first = book.SheetNames[0] ?? '';
      activeSheet.value = first;
      if (first !== '') {
        buildGrid(book, first);
      }
      emit('loaded');
    } catch (err) {
      const reason = err instanceof Error ? err.message : '未知原因';
      error.value = `Excel 解析失败：${reason}`;
      emit('failed', error.value);
    } finally {
      loading.value = false;
    }
  },
  { immediate: true },
);

defineExpose({ revealAnchor });
</script>

<style scoped lang="css">
.xlsx-preview {
  display: flex;
  min-height: 0;
  height: 100%;
  flex-direction: column;
}

/* Sheet 切换条：固定在本区顶部，可横向滚动（表多时不换行） */
.xlsx-preview-bar {
  display: flex;
  flex: none;
  align-items: center;
  gap: 4px;
  overflow: auto hidden;
  padding: 6px 8px;
  border-bottom: 1px solid var(--kb-line);
  background: var(--kb-bg-2);
}

.xlsx-preview-tab {
  flex: none;
  padding: 3px 10px;
  border: 1px solid var(--kb-line);
  border-radius: 4px;
  background: transparent;
  color: var(--kb-text-2);
  font-family: 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif;
  font-size: 12px;
  cursor: pointer;
}

/* 当前工作表 */
.xlsx-preview-tab.is-on {
  border-color: var(--kb-primary);
  background: var(--kb-tint);
  color: var(--kb-primary);
  font-weight: 600;
}

.xlsx-preview-divider {
  flex: 1;
}

.xlsx-preview-info {
  flex: none;
  color: var(--kb-text-3);
  font-family: 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif;
  font-size: 11px;
}

.xlsx-preview-hint {
  flex: none;
  margin: 6px 8px;
  color: var(--kb-text-3);
  font-family: 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif;
  font-size: 11px;
}

.xlsx-preview-hint-bad {
  color: var(--kb-warn);
}

/* 表格区：本组件唯一的滚动容器 */
.xlsx-preview-scroll {
  flex: 1;
  min-height: 0;
  overflow: auto;
  background: var(--kb-bg-0);
}

.xlsx-preview-grid {
  border-collapse: collapse;
  background: var(--kb-preview-bg);
  color: var(--kb-preview-text);
  font-family: 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif;
  font-size: 12px;
}

/* 行号列：粘在左侧，横向滚动时保持可见 */
.xlsx-preview-rownum {
  position: sticky;
  left: 0;
  padding: 2px 6px;
  border: 1px solid var(--kb-line-2);
  background: var(--kb-bg-2);
  color: var(--kb-text-3);
  font-weight: 400;
  text-align: right;
}

.xlsx-preview-cell {
  max-width: 220px;
  overflow: hidden;
  padding: 2px 6px;
  border: 1px solid var(--kb-line-2);
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
}
</style>
