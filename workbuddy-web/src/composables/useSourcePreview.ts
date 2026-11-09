import { computed, ref } from 'vue';

import { getSourceFile } from '@/api/pipeline';

/** 预览形态：白名单里的类型分派到对应渲染器，其余走降级 */
export type PreviewKind = 'pdf' | 'docx' | 'xlsx' | 'none';

export interface UseSourcePreviewOptions {
  /** 文件名（判后缀、拼展示文案用） */
  fileName: () => string;
  /** 源文件引用（知识库文件里的 fileId；缺失时预览与下载都不可用） */
  sourceFileId: () => string;
}

/** 取 axios 错误里的 HTTP 状态码，拼成可读后缀 */
function httpStatusText(err: unknown): string {
  if (err === null || typeof err !== 'object' || !('response' in err)) {
    return '';
  }
  const response = err.response;
  if (response === null || typeof response !== 'object' || !('status' in response)) {
    return '';
  }
  return typeof response.status === 'number' ? `（HTTP ${response.status}）` : '';
}

/** 把 JSON 错误信封读成 `code msg`；读不出来时给原文串 */
async function errorEnvelopeText(blob: Blob): Promise<string> {
  let parsed: unknown;
  try {
    parsed = JSON.parse(await blob.text());
  } catch {
    return '服务端返回的不是文件内容';
  }
  let code: number | '—' = '—';
  let msg = '服务端返回了错误信息';
  if (parsed !== null && typeof parsed === 'object') {
    if ('code' in parsed && typeof parsed.code === 'number') {
      code = parsed.code;
    }
    if ('msg' in parsed && typeof parsed.msg === 'string') {
      msg = parsed.msg;
    }
  }
  return `${code} ${msg}`;
}

/**
 * 原文件字节与预览状态：取字节、判形态、记页数。
 *
 * <p>只依赖文件引用，与解析详情各自独立（解析失败也照常预览）。
 * 失败分三类报，各自的下一步动作不同：没有文件引用 → 数据问题；HTTP 非 2xx → 取文件问题；
 * 拿到 JSON 错误信封 → 后端拒了这次读取。
 */
export function useSourcePreview(options: UseSourcePreviewOptions) {
  /** 原文件字节（预览用）；null 表示还没取到 */
  const sourceBlob = ref<Blob | null>(null);
  const sourceError = ref('');
  /** 预览当前页（0 = 预览还没就绪） */
  const previewPage = ref(0);
  const previewReady = computed(() => previewPage.value > 0);
  /** 原文件总页数（预览加载完成后由子组件告知） */
  const previewTotal = ref(0);

  /** 文件名后缀（大写下发给判断）；无后缀时为空 */
  const fileExt = computed(() => {
    const name = options.fileName();
    const dot = name.lastIndexOf('.');
    return dot < 0 ? '' : name.slice(dot + 1).toUpperCase();
  });

  const fileKindText = computed(() => (fileExt.value ? `${fileExt.value} 文件` : '该文件'));

  const fileSizeText = computed(() => {
    const size = sourceBlob.value?.size ?? 0;
    if (size <= 0) {
      return '';
    }
    if (size < 1024) {
      return `${size} B`;
    }
    if (size < 1024 * 1024) {
      return `${(size / 1024).toFixed(1)} KB`;
    }
    return `${(size / 1024 / 1024).toFixed(1)} MB`;
  });

  /**
   * 预览形态：白名单（`knowledge.file.enabled-formats`）= PDF/DOC/DOCX/XLS/XLSX；
   * 其中只有 PDF/DOCX/XLSX 有可用的浏览器端渲染器，DOC/XLS 与图片走降级
   * （图片在建档校验期就被拒，不会到这里）。
   */
  const previewKind = computed<PreviewKind>(() => {
    if (fileExt.value === 'PDF') {
      return 'pdf';
    }
    if (fileExt.value === 'DOCX') {
      return 'docx';
    }
    if (fileExt.value === 'XLSX') {
      return 'xlsx';
    }
    return 'none';
  });

  /** 当前类型是否支持内嵌预览（决定提示文案与是否显示"可定位 N 个元素"） */
  const canPreview = computed(() => previewKind.value !== 'none');

  /** 取原文件字节 */
  async function loadSource(): Promise<void> {
    sourceError.value = '';
    sourceBlob.value = null;
    previewPage.value = 0;
    const fileId = options.sourceFileId();
    if (!fileId) {
      sourceError.value = '这条知识库文件没有可用的源文件引用';
      return;
    }
    try {
      const blob = await getSourceFile(fileId);
      if (blob.size === 0) {
        sourceError.value = '原文件内容为空（服务端返回 0 字节）';
        return;
      }
      // 下载失败时后端仍以 200 + JSON 错误信封返回，这里把它认出来
      if (blob.type.includes('json')) {
        sourceError.value = `取原文件失败：${await errorEnvelopeText(blob)}`;
        return;
      }
      sourceBlob.value = blob;
    } catch (err) {
      sourceError.value = `取原文件失败${httpStatusText(err)}`;
    }
  }

  /** 预览就绪：记下总页数 / 工作表数，并把首开页上报为第 1 页 */
  function onPreviewLoaded(total: number): void {
    previewTotal.value = total;
    if (previewPage.value === 0) {
      previewPage.value = 1;
    }
  }

  /** Word / Excel 预览就绪：这两类没有页概念，栏头只显示文件信息与可定位元素数 */
  function onOfficeLoaded(): void {
    previewTotal.value = 0;
  }

  /** 换运行后回到未取数状态 */
  function reset(): void {
    sourceBlob.value = null;
    sourceError.value = '';
    previewPage.value = 0;
    previewTotal.value = 0;
  }

  return {
    sourceBlob,
    sourceError,
    previewPage,
    previewReady,
    previewTotal,
    fileExt,
    fileKindText,
    fileSizeText,
    previewKind,
    canPreview,
    loadSource,
    onPreviewLoaded,
    onOfficeLoaded,
    reset,
  };
}
