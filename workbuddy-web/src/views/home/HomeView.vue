<template>
  <div class="page">
    <div class="page-panel">
      <!--
        资产带：用共用的 .page-assets（竖排 标签/数值/副文案），收进面板顶部。
      -->
      <div class="page-assets page-assets-inline">
        <div class="page-asset">
          <span class="page-asset-k">知识库</span>
          <span class="page-asset-v">{{ summaryText.knowledgeBaseCount }}</span>
          <span class="page-asset-sub">启用 {{ summaryText.enabledKnowledgeBaseCount }}</span>
        </div>
        <div class="page-asset">
          <span class="page-asset-k">文档</span>
          <span class="page-asset-v">{{ summaryText.documentCount }}</span>
          <span class="page-asset-sub">已建档文档</span>
        </div>
        <div class="page-asset">
          <span class="page-asset-k">可用策略</span>
          <span class="page-asset-v">{{ summaryText.strategyVersionCount }}</span>
          <span class="page-asset-sub">
            预处理 {{ summaryText.preprocessVersionCount }} · 切片
            {{ summaryText.chunkVersionCount }} · 向量化 {{ summaryText.embedVersionCount }} · 检索
            {{ summaryText.retrievalVersionCount }}
          </span>
        </div>
      </div>

      <!--
        最近提交：**只含本人**的记录（后端按登录用户过滤，前端不传提交人），
        没有"提交人"列。面板走共用骨架：标题在 .page-toolbar，计数与翻页在 .page-panel-foot。
      -->
      <div class="page-toolbar">
        <span class="home-panel-title">最近提交</span>
        <span class="page-spacer"></span>
      </div>

      <!--
        表体 flex:1 + min-height:0：高度由面板决定，**与本页记录数无关** ——
        末页只有两三条时容器也保持同高，翻页不会跳。窗口太矮时是表格内部滚动。
        加载态用 v-loading 遮罩，不能用 v-if 换掉表格（翻页会闪）；空态走 #empty 插槽。
        容器、表格主题与列宽/对齐口径见 styles/page-shell.css 的「列表表格」一节。
      -->
      <div class="page-table-body">
        <el-table
          v-loading="submitLoading"
          class="page-table"
          :data="submits"
          :border="false"
          height="100%"
        >
          <el-table-column label="文件名" min-width="120" align="center">
            <template #default="{ row }">
              <span class="home-file home-ellipsis" :title="row.fileName">{{
                row.fileName || '（文件名不可得）'
              }}</span>
            </template>
          </el-table-column>
          <el-table-column label="文件类型" min-width="120" align="center">
            <template #default="{ row }">
              <!-- 类型来自来源文件的 MIME（不按扩展名猜）；校验失败的行取不到，显示占位符 -->
              <span class="home-mono home-dim">{{ row.fileType ?? '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="知识库" min-width="120" align="center">
            <template #default="{ row }">
              <span class="home-dim home-ellipsis" :title="row.knowledgeBaseName">{{
                row.knowledgeBaseName
              }}</span>
            </template>
          </el-table-column>
          <el-table-column label="结果" min-width="120" align="center">
            <template #default="{ row }">
              <span
                class="home-act"
                :class="{ 'is-ok': row.status === 'PASS', 'is-danger': row.status === 'FAIL' }"
              >
                {{ row.status === 'PASS' ? '通过' : '未通过' }}
              </span>
              <span v-if="row.failReasonLabel" class="home-reason">{{ row.failReasonLabel }}</span>
            </template>
          </el-table-column>
          <el-table-column label="提交时间" min-width="120" align="center">
            <template #default="{ row }">
              <span class="home-mono home-dim">{{ formatSubmitTime(row.createTime) }}</span>
            </template>
          </el-table-column>
          <template #empty>
            <div class="page-table-empty">
              {{ submitLoading ? '加载中…' : '还没有提交记录' }}
            </div>
          </template>
        </el-table>
      </div>

      <div class="page-panel-foot">
        <span>共 {{ submitTotal }} 条</span>
        <el-pagination
          v-model:current-page="submitQuery.current"
          class="home-pager"
          layout="prev, pager, next"
          :total="submitTotal"
          :page-size="PAGE_SIZE"
          :disabled="submitLoading"
          @current-change="loadSubmits"
        />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 系统首页：**资产速览 + 最近提交（仅本人）**。
 *
 * <p>两块内容，回答两个问题：
 *
 * <ul>
 *   <li>**资产速览**（最上面）—— "现在有什么、能用什么"。细分区给"启用数 / 四类可用策略"，
 *       而不是库存总数：索引版本那种数字在没用起来时恒为 0，列出来等于没信息，
 *       而且知识库卡片上已经有「索引版本」一格，首页再列是重复；</li>
 *   <li>**最近提交**（`wb_submit_log`）—— **当前登录用户**的文件提交。`status` 是
 *       **提交校验结果**（PASS/FAIL），**不是处理链进度**，工具栏里写明了这句。
 *       可见范围由后端按登录用户过滤，前端不传提交人，没有"提交人"列。</li>
 * </ul>
 *
 * <p>每页固定 {@link PAGE_SIZE} 条：列表容器高度由面板决定、与本页记录数无关，
 * 末页条数少时不会塌下去，翻页也不会跳。
 */
import { computed, onMounted, reactive, ref } from 'vue';

import { getHomeRecentSubmits, getHomeSummary } from '@/api/home';
import { formatSubmitTime } from '@/types/home';
import type { HomeRecentSubmit, HomeSummary } from '@/types/home';

/** 固定每页条数（页长不随窗口高度变化，翻页用页码） */
const PAGE_SIZE = 10;

const summary = ref<HomeSummary | null>(null);

/** 最近提交（仅本人） */
const submits = ref<HomeRecentSubmit[]>([]);
const submitLoading = ref(false);
const submitTotal = ref(0);
const submitQuery = reactive({ current: 1, size: PAGE_SIZE });

/**
 * 数字按千分位展示；未加载完显示占位符，避免闪 0。
 *
 * <p>只映射界面上真实展示的字段：后端不下发索引版本那两个计数
 * （知识库卡片上已有「索引版本」一格）。
 */
const summaryText = computed(() => {
  const pick = (value: string | number | undefined): string => formatCount(value);
  return {
    knowledgeBaseCount: pick(summary.value?.knowledgeBaseCount),
    enabledKnowledgeBaseCount: pick(summary.value?.enabledKnowledgeBaseCount),
    strategyVersionCount: pick(summary.value?.strategyVersionCount),
    preprocessVersionCount: pick(summary.value?.preprocessVersionCount),
    chunkVersionCount: pick(summary.value?.chunkVersionCount),
    embedVersionCount: pick(summary.value?.embedVersionCount),
    retrievalVersionCount: pick(summary.value?.retrievalVersionCount),
    documentCount: pick(summary.value?.documentCount),
  };
});

function formatCount(value: string | number | undefined): string {
  if (value === undefined || value === '') {
    return '—';
  }
  // 计数在小数值区间（后端全局口径：只有超过 JS 安全整数的雪花 ID 才下发字符串），
  // 这里拿到的是 number；仍兼容字符串入参，避免显示成 —
  const num = Number(value);
  return Number.isNaN(num) ? '—' : num.toLocaleString('en-US');
}

async function loadSummary(): Promise<void> {
  try {
    summary.value = await getHomeSummary();
  } catch {
    // 失败提示已由接口层统一拦截；这里保持占位符
  }
}

async function loadSubmits(): Promise<void> {
  submitLoading.value = true;
  try {
    const page = await getHomeRecentSubmits({
      current: submitQuery.current,
      size: submitQuery.size,
    });
    submits.value = page.records;
    submitTotal.value = page.total;
  } catch {
    submits.value = [];
    submitTotal.value = 0;
  } finally {
    submitLoading.value = false;
  }
}

onMounted(() => {
  void loadSummary();
  void loadSubmits();
});
</script>

<style scoped lang="css">
/*
 * 页面骨架来自全局 styles/page-shell.css：
 *   .page-head / .page-title / .page-desc  头部
 *   .page-assets / .page-asset*            资产带（竖排 标签/数值/副文案）
 *   .page-panel / .page-toolbar / .page-panel-foot  内容面板
 *   .page-table / .page-table-body / .page-table-empty  最近提交表格
 * 本页只保留业务样式。
 */

/* 面板标题（放进共用的 .page-toolbar） */
.home-panel-title {
  font-size: 13px;
  font-weight: 650;
}

/* 分页：总数在左侧那行文字里，这里只放翻页按钮与当前页 */
.home-pager {
  --el-pagination-button-bg-color: transparent;
  --el-pagination-text-color: var(--kb-text-2);
  --el-pagination-button-color: var(--kb-text-2);
  --el-pagination-hover-color: var(--kb-primary);
}

.home-mono {
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
  font-size: 11px;
}

.home-dim {
  color: var(--kb-text-3);
}

/*
 * 单行截断：表格行高必须恒定。
 * 完整内容用 title 属性承载（见模板）。
 */
.home-ellipsis {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.home-file {
  color: var(--kb-text-1);
}

/* 失败原因：跟在结果徽标后面，弱化显示 */
.home-reason {
  margin-left: 7px;
  color: var(--kb-text-4);
  font-size: 11px;
}

/* 结果徽标：通过 / 未通过 两档配色 */
.home-act {
  display: inline-flex;
  align-items: center;
  height: 19px;
  padding: 0 8px;
  border: 1px solid var(--kb-line-2);
  border-radius: 5px;
  font-size: 11px;
  white-space: nowrap;
}

.home-act.is-ok {
  border-color: rgb(52 211 153 / 35%);
  background: rgb(52 211 153 / 10%);
  color: var(--kb-primary);
}

.home-act.is-danger {
  border-color: rgb(248 113 113 / 32%);
  background: rgb(248 113 113 / 9%);
  color: var(--kb-danger);
}
</style>
