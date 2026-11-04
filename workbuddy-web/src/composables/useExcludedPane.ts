import { computed, ref } from 'vue';

import { getStageContent } from '@/api/pipeline';
import type { StageContentItem } from '@/types/pipeline';
import { PREPROCESS_CHUNK_SKIP_STATUSES } from '@/types/pipeline-stage-view';

/** 取数所需的上下文（用取值函数，组件里改选文件/任务后不必重建状态） */
export interface ExcludedPaneContext {
  fileResultId: () => string;
  stage: () => string;
  taskId: () => string;
  pageSize: number;
}

/**
 * 「剔除内容」页签：只取不进切片的元素（剔除态 + 重复份），按需加载、按页取数。
 *
 * <p>失败时不向上抛：提示已由接口层统一拦截，这里只落一个本页签可见的错误文案。
 */
export function useExcludedPane(ctx: ExcludedPaneContext) {
  const page = ref(1);
  const items = ref<StageContentItem[]>([]);
  const total = ref(0);
  const loading = ref(false);
  const error = ref('');
  /** 是否已取过（切到该页签时按需加载） */
  const loaded = ref(false);

  const pageCount = computed(() => Math.max(1, Math.ceil(total.value / ctx.pageSize)));

  /** 拉某一页（过滤条件随请求下发，过滤与分页都在服务端做） */
  async function load(): Promise<void> {
    loading.value = true;
    error.value = '';
    try {
      const data = await getStageContent(ctx.fileResultId(), ctx.stage(), {
        taskId: ctx.taskId(),
        status: PREPROCESS_CHUNK_SKIP_STATUSES.join(','),
        current: page.value,
        size: ctx.pageSize,
      });
      items.value = data.items ?? [];
      total.value = data.total ?? 0;
      loaded.value = true;
    } catch {
      // 失败提示已由接口层统一拦截处理
      items.value = [];
      total.value = 0;
      error.value = '剔除内容加载失败';
    } finally {
      loading.value = false;
    }
  }

  function go(next: number): void {
    const target = Math.min(Math.max(next, 1), pageCount.value);
    if (target === page.value) {
      return;
    }
    page.value = target;
    void load();
  }

  /** 换运行后回到未取数状态，下次切到该页签再取 */
  function reset(): void {
    page.value = 1;
    items.value = [];
    total.value = 0;
    error.value = '';
    loaded.value = false;
  }

  return { page, items, total, loading, error, loaded, pageCount, load, go, reset };
}
