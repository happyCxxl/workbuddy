import { reactive } from 'vue';

import { getStageContent } from '@/api/pipeline';
import type { StageContentItem } from '@/types/pipeline';

/** 页签取数的过滤条件：两个切片页签各自只关心自己的片 */
export interface ChunkPaneFilter {
  fallback?: boolean;
  hasParent?: boolean;
}

/** 取数所需的上下文（用取值函数，组件里改选文件/任务后不必重建页签） */
export interface ChunkPaneContext {
  knowledgeFileId: () => string;
  stage: () => string;
  taskId: () => string;
  pageSize: number;
}

/** 一个按过滤条件单独取数的切片页签：过滤与分页都在服务端做 */
export interface ChunkPane {
  page: number;
  items: StageContentItem[];
  total: number;
  loading: boolean;
  error: string;
  /** 是否已成功取过一页（决定首次进入页签时是否需要触发取数） */
  loaded: boolean;
  load: () => Promise<void>;
  go: (next: number) => void;
  reset: () => void;
}

/**
 * 建一个切片过滤页签的状态：两个页签各自翻页、各自按需取数。
 *
 * <p>失败时不向上抛：提示已由接口层统一拦截，这里只落一个本页签可见的错误文案。
 */
export function createChunkPane(filter: ChunkPaneFilter, ctx: ChunkPaneContext): ChunkPane {
  const pane = reactive<ChunkPane>({
    page: 1,
    items: [],
    total: 0,
    loading: false,
    error: '',
    loaded: false,
    load: async () => undefined,
    go: () => undefined,
    reset: () => undefined,
  });
  const pageCount = (): number => Math.max(1, Math.ceil(pane.total / ctx.pageSize));
  pane.load = async (): Promise<void> => {
    pane.loading = true;
    pane.error = '';
    try {
      const data = await getStageContent(ctx.knowledgeFileId(), ctx.stage(), {
        taskId: ctx.taskId(),
        ...filter,
        current: pane.page,
        size: ctx.pageSize,
      });
      pane.items = data.items ?? [];
      pane.total = data.total ?? 0;
      pane.loaded = true;
    } catch {
      // 失败提示已由接口层统一拦截处理
      pane.items = [];
      pane.total = 0;
      pane.error = '切片内容加载失败';
    } finally {
      pane.loading = false;
    }
  };
  pane.go = (next: number): void => {
    const target = Math.min(Math.max(next, 1), pageCount());
    if (target === pane.page) {
      return;
    }
    pane.page = target;
    void pane.load();
  };
  pane.reset = (): void => {
    pane.page = 1;
    pane.items = [];
    pane.total = 0;
    pane.error = '';
    pane.loaded = false;
  };
  return pane;
}
