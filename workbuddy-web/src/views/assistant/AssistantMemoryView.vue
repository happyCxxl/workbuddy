<template>
  <div class="page">
    <div class="page-panel">
      <div class="page-toolbar">
        <span class="memory-chip">共 {{ records.length }} 条</span>
        <span class="memory-chip">全局 {{ globalCount }} · 目录级 {{ dirCount }}</span>
        <el-radio-group v-model="scopeFilter" size="small">
          <el-radio-button value="">全部</el-radio-button>
          <el-radio-button value="global">全局</el-radio-button>
          <el-radio-button value="dir">目录级</el-radio-button>
        </el-radio-group>
        <el-input v-model="keyword" class="memory-search" placeholder="搜索记忆内容" clearable />
        <div class="page-spacer"></div>
        <button
          class="page-refresh"
          type="button"
          title="刷新"
          :disabled="loading"
          @click="loadMemories"
        >
          <svg
            :class="{ 'page-refresh-spin': loading }"
            width="14"
            height="14"
            viewBox="0 0 16 16"
            fill="none"
            stroke="currentColor"
            stroke-width="1.5"
          >
            <path d="M13.4 8a5.4 5.4 0 1 1-1.6-3.8M13.4 1.9v2.4H11" />
          </svg>
        </button>
      </div>
      <div class="page-table-body">
        <el-table v-loading="loading" class="page-table" height="100%" :data="filteredRecords">
          <el-table-column label="类型" min-width="150" align="center">
            <template #default="{ row }">
              <el-tag :type="kindTagType(row.kind)" effect="plain" size="small">
                {{ kindLabel(row.kind) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="内容" min-width="150" align="center">
            <template #default="{ row }">{{ row.content }}</template>
          </el-table-column>
          <el-table-column label="作用域" min-width="150" align="center">
            <template #default="{ row }">
              <el-tag
                :type="row.scope === 'global' ? 'success' : 'info'"
                effect="plain"
                size="small"
              >
                {{ row.scope === 'global' ? '全局' : '目录级' }}
              </el-tag>
              <span v-if="row.work_dir" class="memory-dir">{{ row.work_dir }}</span>
            </template>
          </el-table-column>
          <el-table-column label="来源时间" min-width="150" align="center">
            <template #default="{ row }">{{ formatDateTime(row.created_at) }}</template>
          </el-table-column>
          <el-table-column label="操作" min-width="150" align="center">
            <template #default="{ row }">
              <el-button link type="danger" size="small" @click="handleDelete(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <div class="page-panel-foot">
        <span>删除后不可恢复；对话历史不受影响。</span>
        <span class="memory-vector">向量化：BGE-M3 · 混合召回 top-8</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus';
import { computed, onMounted, ref } from 'vue';

import { deleteMemory, getMemories } from '@/api/assistant';
import type { AssistantMemory } from '@/types/assistant';
import { formatDateTime } from '@/utils/date';

const records = ref<AssistantMemory[]>([]);
const loading = ref(false);
const scopeFilter = ref('');
const keyword = ref('');

/** 作用域统计 */
const globalCount = computed(() => records.value.filter((row) => row.scope === 'global').length);
const dirCount = computed(() => records.value.filter((row) => row.scope === 'dir').length);

/** 过滤后的记录：作用域 + 关键字 */
const filteredRecords = computed(() =>
  records.value.filter((row) => {
    if (scopeFilter.value && row.scope !== scopeFilter.value) {
      return false;
    }
    return !(keyword.value && !row.content.includes(keyword.value));
  }),
);

/** 类型中文名 */
function kindLabel(kind: string): string {
  if (kind === 'preference') {
    return '偏好';
  }
  if (kind === 'ongoing') {
    return '进行中';
  }
  return '事实';
}

/** 类型标签色 */
function kindTagType(kind: string): 'success' | 'warning' | 'info' {
  if (kind === 'preference') {
    return 'success';
  }
  if (kind === 'ongoing') {
    return 'warning';
  }
  return 'info';
}

/** 载入记忆：直查记忆表（全量） */
async function loadMemories(): Promise<void> {
  loading.value = true;
  try {
    records.value = await getMemories();
  } finally {
    loading.value = false;
  }
}

/** 删除记忆：物理移除 */
async function handleDelete(row: Record<string, unknown>): Promise<void> {
  const memoryId = String(row['id'] ?? '');
  const content = String(row['content'] ?? '');
  try {
    await ElMessageBox.confirm(
      `删除记忆「${content.slice(0, 30)}」？删除后不可恢复。`,
      '删除记忆',
      {
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        type: 'warning',
      },
    );
  } catch {
    return;
  }
  await deleteMemory(memoryId);
  ElMessage.success('已删除');
  await loadMemories();
}

onMounted(loadMemories);
</script>

<style scoped lang="css">
.memory-search {
  width: 240px;
}

.memory-chip {
  padding: 2px 10px;
  border: 1px solid var(--kb-line);
  border-radius: 999px;
  background: var(--kb-surface);
  color: var(--kb-text-2);
  font-size: 12px;
}

.memory-dir {
  margin-left: 8px;
  color: var(--kb-text-3);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 11px;
}

.memory-vector {
  color: var(--kb-text-3);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 11px;
}
</style>
