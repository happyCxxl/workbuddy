<template>
  <div class="page">
    <div class="page-head">
      <!-- 标题与说明要包一层：.page-head 是 flex 行，不包会与右侧按钮并排 -->
      <div>
        <h1 class="page-title">用量与成本</h1>
        <p class="page-desc">按时间区间查看模型调用明细与成本统计</p>
      </div>
    </div>

    <div class="page-panel">
      <div class="page-toolbar">
        <el-date-picker
          v-model="usageRange"
          class="model-range"
          type="daterange"
          value-format="YYYY-MM-DD"
          :clearable="false"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          @change="loadUsage"
        />
        <el-select v-model="usageCapability" class="model-select" placeholder="能力">
          <el-option label="全部能力" value="" />
          <el-option
            v-for="item in MODEL_CAPABILITIES"
            :key="item"
            :label="MODEL_CAPABILITY_LABELS[item]"
            :value="item"
          />
        </el-select>
        <el-select v-model="usageRouteReason" class="model-select" placeholder="路由原因">
          <el-option label="全部原因" value="" />
          <el-option
            v-for="item in ROUTE_REASONS"
            :key="item"
            :label="ROUTE_REASON_LABELS[item]"
            :value="item"
          />
        </el-select>
        <el-button class="page-btn-primary" @click="loadUsage">查询</el-button>
        <button
          class="page-refresh"
          type="button"
          title="刷新"
          :disabled="usageLoading"
          @click="loadUsage"
        >
          <svg
            :class="{ 'page-refresh-spin': usageLoading }"
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

      <div class="model-usage-body">
        <div class="model-stats">
          <div class="model-stat">
            <span class="model-stat-k">调用总数</span>
            <span class="model-stat-v">{{ summary.calls ?? '—' }}</span>
            <span class="model-stat-s">
              成功 {{ summary.successCalls ?? 0 }} · 失败 {{ summary.failedCalls ?? 0 }}
            </span>
          </div>
          <div class="model-stat">
            <span class="model-stat-k">已知成本合计</span>
            <span class="model-stat-v">{{ moneyText(summary.costTotal) }}</span>
            <span class="model-stat-s">未知用量行不参与合计</span>
          </div>
          <div class="model-stat">
            <span class="model-stat-k">未知用量占比</span>
            <span class="model-stat-v">{{ ratioText(summary.unknownRatio) }}</span>
            <span class="model-stat-s"
              >未知 {{ summary.unknownCalls ?? 0 }} 条 · 三项 token 与成本记 NULL</span
            >
          </div>
          <div class="model-stat">
            <span class="model-stat-k">切换次数（按 route_reason）</span>
            <span class="model-stat-v">{{ switchCount }}</span>
            <span class="model-stat-s">{{ switchSummaryText }}</span>
          </div>
        </div>

        <p class="model-usage-note">
          明细一次调用一行；`usage_unknown = 1` 的行三项 token 与成本记 NULL（未知），写 0
          表示确实为零，两者不混用。
        </p>

        <!-- 列宽口径同上：9 列同 min-width 即等宽 -->
        <el-table
          v-loading="usageLoading"
          class="page-table model-usage-table"
          height="360"
          :data="calls"
          :border="false"
        >
          <el-table-column label="时间" min-width="120" align="center">
            <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
          </el-table-column>
          <el-table-column label="业务" min-width="120" align="center">
            <template #default="{ row }">
              <span class="model-muted">{{ row.bizType || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="能力" min-width="120" align="center">
            <template #default="{ row }">
              <span class="model-tag">{{ row.capability || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="模型" min-width="120" align="center">
            <template #default="{ row }">
              <span class="model-mono">{{ row.model || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="用量（入 / 出）" min-width="120" align="center">
            <template #default="{ row }">
              <span class="model-mono" :class="{ 'model-muted': row.usageUnknown === 1 }">
                {{ usageText(row as ModelCallLog) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="成本" min-width="120" align="center">
            <template #default="{ row }">
              <span class="model-mono" :class="{ 'model-muted': row.cost === null }">
                {{ row.cost === null ? 'NULL' : moneyText(row.cost) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="延迟" min-width="120" align="center">
            <template #default="{ row }">
              <span class="model-mono">{{
                row.latencyMs === null ? '—' : `${row.latencyMs}ms`
              }}</span>
            </template>
          </el-table-column>
          <el-table-column label="路由原因" min-width="120" align="center">
            <template #default="{ row }">
              <span v-if="row.routeReason" class="model-tag">{{ row.routeReason }}</span>
              <span v-else class="model-muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" min-width="120" align="center">
            <template #default="{ row }">
              <el-tag
                :type="row.status === CALL_STATUS.SUCCESS ? 'success' : 'danger'"
                effect="dark"
                size="small"
                :title="row.errorCode || ''"
              >
                {{ CALL_STATUS_LABELS[row.status] || row.status || '—' }}
              </el-tag>
            </template>
          </el-table-column>
          <template #empty>
            <div class="page-table-empty">{{ usageLoading ? '加载中…' : '暂无调用明细' }}</div>
          </template>
        </el-table>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';

import { getModelUsageCalls, getModelUsageSummary } from '@/api/model';
import {
  CALL_STATUS,
  CALL_STATUS_LABELS,
  MODEL_CAPABILITIES,
  MODEL_CAPABILITY_LABELS,
  ROUTE_REASON_LABELS,
  ROUTE_REASONS,
  type ModelCallLog,
  type ModelCallLogSummary,
} from '@/types/model';
import { formatDateTime } from '@/utils/date';

// 用量与成本页：概览与调用明细；接口层已统一处理失败提示

/** 路由原因里表示常规选型的取值：其余取值都算一次切换 */
const ROUTE_REASON_NORMAL = 'OK';

/** 明细查询条数上限（后端未传 500、上限 2000）：切换次数按这一批明细统计 */
const USAGE_LIMIT = 500;

const usageLoading = ref(false);
const usageRange = ref<string[] | null>([todayText(), todayText()]);
const usageCapability = ref('');
const usageRouteReason = ref('');
const summary = ref<ModelCallLogSummary>(emptySummary());
const calls = ref<ModelCallLog[]>([]);

/** 明细里的路由原因分布（不含常规选型那一档），按条数降序 */
const routeReasonStats = computed(() => {
  const counts = new Map<string, number>();
  for (const item of calls.value) {
    const reason = item.routeReason;
    if (reason !== null && reason !== ROUTE_REASON_NORMAL) {
      counts.set(reason, (counts.get(reason) ?? 0) + 1);
    }
  }
  return [...counts.entries()].sort((left, right) => right[1] - left[1]);
});

const switchCount = computed(() => routeReasonStats.value.reduce((sum, item) => sum + item[1], 0));

const switchSummaryText = computed(() => {
  const stats = routeReasonStats.value;
  if (stats.length === 0) {
    return `最近 ${calls.value.length} 条明细里没有切换`;
  }
  return stats.map(([reason, count]) => `${reason} ${count}`).join(' · ');
});

/** 空概览：范围内没有调用时聚合列为 null，与「全部已知」区分 */
function emptySummary(): ModelCallLogSummary {
  return {
    calls: null,
    successCalls: null,
    failedCalls: null,
    timeoutCalls: null,
    rateLimitedCalls: null,
    unknownCalls: null,
    unknownRatio: null,
    totalTokens: null,
    costTotal: null,
  };
}

/** 当日日期文本（yyyy-MM-dd）：用量查询的默认区间两段都是今天 */
function todayText(): string {
  const now = new Date();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return `${now.getFullYear()}-${month}-${day}`;
}

/** 日期文本加一天（用量区间右端按不含计，取次日零点） */
function nextDayText(text: string): string {
  const date = new Date(`${text}T00:00:00`);
  date.setDate(date.getDate() + 1);
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${date.getFullYear()}-${month}-${day}`;
}

/** 用量查询的时间两段（from 含 / to 不含）；区间读不到时按今天兜底 */
function usageTimeRange(): { from: string; to: string } {
  const range = usageRange.value;
  if (range === null || range.length < 2) {
    const today = todayText();
    return { from: today, to: nextDayText(today) };
  }
  return { from: range[0], to: nextDayText(range[1]) };
}

/** 金额展示：最多六位小数，去掉末尾的零；空值读作 — */
function moneyText(value: number | null): string {
  if (value === null) {
    return '—';
  }
  const text = Number(value).toFixed(6).replace(/0+$/, '').replace(/\.$/, '');
  return text === '' ? '0' : text;
}

/** 占比展示：后端下发四位小数（0.0081 → 0.81%），空值读作 — */
function ratioText(value: number | null): string {
  if (value === null) {
    return '—';
  }
  return `${(Number(value) * 100).toFixed(2)}%`;
}

/** 用量列文案：用量标记为未知或两项 token 缺项时读 NULL，其余读「入 / 出」 */
function usageText(item: ModelCallLog): string {
  if (item.usageUnknown === 1 || item.promptTokens === null || item.completionTokens === null) {
    return 'NULL';
  }
  return `${item.promptTokens} / ${item.completionTokens}`;
}

async function loadUsage(): Promise<void> {
  usageLoading.value = true;
  const { from, to } = usageTimeRange();
  const query = {
    from,
    to,
    capability: usageCapability.value === '' ? undefined : usageCapability.value,
    routeReason: usageRouteReason.value === '' ? undefined : usageRouteReason.value,
    limit: USAGE_LIMIT,
  };
  try {
    summary.value = await getModelUsageSummary(query);
    calls.value = await getModelUsageCalls(query);
  } catch {
    // 失败提示已由接口层统一拦截
  } finally {
    usageLoading.value = false;
  }
}

onMounted(async () => {
  await loadUsage();
});
</script>

<style scoped lang="css">
/*
 * 页面骨架用全局 styles/page-shell.css 的 .page / .page-head / .page-title / .page-desc /
 * .page-panel / .page-toolbar；本页只补工具条控件宽度、概览卡与明细表的私有类。
 */

/* ==================== 工具条控件宽度 ==================== */
.model-select {
  width: 150px;
}

.model-range {
  width: 260px;
}

/* ==================== 表格内的文本样式 ==================== */
.model-muted {
  color: var(--kb-text-3);
  font-size: 12px;
}

.model-mono {
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
}

/* 能力与路由原因标签：描边小标签，多个标签之间留一点间距 */
.model-tag {
  display: inline-block;
  margin: 1px 3px;
  padding: 1px 7px;
  border: 1px solid rgb(52 211 153 / 35%);
  border-radius: 6px;
  background: var(--kb-tint);
  color: var(--kb-primary);
  font-size: 11px;
}

/* ==================== 用量与成本 ==================== */
.model-usage-body {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 16px;
}

.model-stats {
  display: grid;
  gap: 12px;
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.model-stat {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px 14px;
  border: 1px solid var(--kb-line);
  border-radius: 12px;
  background: rgb(255 255 255 / 3%);
}

.model-stat-k {
  color: var(--kb-text-2);
  font-size: 12px;
}

.model-stat-v {
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
  font-size: 20px;
  font-weight: 650;
}

.model-stat-s {
  color: var(--kb-text-3);
  font-size: 11px;
  line-height: 1.6;
}

.model-usage-note {
  margin: 14px 0 10px;
  color: var(--kb-text-3);
  font-size: 12px;
}

.model-usage-table {
  border: 1px solid var(--kb-line);
  border-radius: 10px;
}
</style>
