<template>
  <div class="page">
    <div class="page-head">
      <!-- 标题与说明要包一层：.page-head 是 flex 行，不包会与右侧按钮并排 -->
      <div>
        <h1 class="page-title">知识库</h1>
        <p class="page-desc">管理你的知识库与文档资产，查看构建与运行状态</p>
      </div>
      <div class="page-actions">
        <el-button class="page-btn-ghost" plain @click="openImport()">导入文档</el-button>
      </div>
    </div>
    <div class="page-stats">
      <div v-for="item in statItems" :key="item.label" class="page-stat">
        <span class="page-stat-num">{{ item.value }}</span>
        <span class="page-stat-label">{{ item.label }}</span>
      </div>
    </div>
    <div class="page-panel">
      <div class="page-toolbar">
        <el-input
          v-model="keyword"
          class="kb-search"
          placeholder="搜索知识库名称"
          clearable
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />
        <div class="kb-filter-group">
          <span class="kb-filter-label">状态</span>
          <button
            v-for="filter in statusFilters"
            :key="filter.label"
            class="kb-chip"
            :class="{ 'kb-chip-on': selectedStatus === filter.value }"
            type="button"
            @click="handleFilter(filter.value)"
          >
            {{ filter.label }}
          </button>
        </div>
        <el-select v-model="sortBy" class="kb-sort-select" @change="handleSort">
          <el-option label="默认（新建在前）" value="DEFAULT" />
          <el-option label="最近更新" value="UPDATED" />
          <el-option label="名称" value="NAME" />
        </el-select>
        <!-- 计数只在页脚说一次：同屏重复两遍没有信息量，却要占掉工具条右端 -->
        <div class="page-spacer"></div>
        <button
          class="page-refresh"
          type="button"
          title="刷新"
          :disabled="loading"
          @click="refresh"
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
      <!--
        虚线卡放**首位**：满页时它在滚动区底部，用户得先滚到底才看得见
        「新建」——一个页面唯一的入口不该藏在列表后面。

        网格**不按 loading 卸载**，改用 v-loading 覆盖层：加载期间列表照旧在原地。
      -->
      <div v-loading="loading" class="kb-grid">
        <button class="kb-new" type="button" @click="openCreate">
          <span class="kb-new-plus">+</span>
          新建知识库
        </button>
        <KnowledgeBaseCard
          v-for="item in kbList"
          :key="item.id"
          :kb="item"
          @update="openEdit"
          @import="openImport(item.id)"
          @toggle="handleToggleStatus"
          @delete="handleDelete"
        />
      </div>
      <div v-if="!loading && kbList.length === 0" class="kb-empty">
        <p v-if="isFiltered" class="kb-empty-text">没有符合当前筛选条件的知识库</p>
        <template v-else>
          <p class="kb-empty-text">还没有启用的知识库</p>
          <p class="kb-empty-hint">点左上角虚线卡即可新建；已停用的库切到「已停用」查看</p>
        </template>
        <el-button v-if="isFiltered" class="page-btn-ghost" @click="handleClearFilter">
          清除筛选
        </el-button>
      </div>
      <div class="page-panel-foot">
        <span>共 {{ total }} 个知识库</span>
        <el-pagination
          v-model:current-page="query.current"
          class="kb-pager"
          layout="prev, pager, next"
          :total="total"
          :page-size="PAGE_SIZE"
          @current-change="loadList"
        />
      </div>
    </div>

    <el-dialog
      v-model="dialogVisible"
      class="kb-dialog"
      :title="isEdit ? '编辑知识库' : '新建知识库'"
      width="440px"
      destroy-on-close
      @closed="handleDialogClosed"
    >
      <el-form
        ref="dialogFormRef"
        :model="dialogForm"
        :rules="dialogRules"
        label-position="top"
        :hide-required-asterisk="true"
      >
        <el-form-item prop="name" label="知识库名称" class="kb-dialog-item">
          <el-input
            v-model="dialogForm.name"
            :maxlength="KB_NAME_MAX"
            show-word-limit
            :placeholder="`如：金融研报库（≤${KB_NAME_MAX} 字符）`"
          />
        </el-form-item>
        <el-form-item prop="description" label="业务场景说明" class="kb-dialog-item">
          <el-input
            v-model="dialogForm.description"
            type="textarea"
            :rows="3"
            :maxlength="KB_DESCRIPTION_MAX"
            show-word-limit
            :placeholder="`如：金融行业 · 研究报告与公告（≤${KB_DESCRIPTION_MAX} 字符）`"
          />
        </el-form-item>
        <el-form-item prop="strategyBindingEnabled" label="策略绑定" class="kb-dialog-item">
          <el-radio-group v-model="dialogForm.strategyBindingEnabled">
            <el-radio :value="1">绑定（触发走本库绑定策略）</el-radio>
            <el-radio :value="0">不绑定（测评模式，触发须显式选策略）</el-radio>
          </el-radio-group>
        </el-form-item>

        <!--
          三件套绑定：**始终渲染、仅按开关禁用**，不用 v-if 显隐 ——
          切换开关时弹窗高度不跳动，禁用态保持可见。
          「开关开着但还没绑」是允许的中间态：建库时先决定要不要绑定，
          具体绑哪个版本要等评测出结果，只在保存时校验完整性。
        -->
        <el-form-item
          v-for="type in BINDABLE_STRATEGY_TYPES"
          :key="type"
          :label="strategyTypeLabel(type)"
          class="kb-dialog-item"
        >
          <el-select
            v-model="dialogForm.strategyVersions[type]"
            class="kb-binding-select"
            placeholder="选择要绑定的版本"
            :loading="strategyLoading"
            :disabled="!bindingEnabled"
            clearable
          >
            <el-option
              v-for="version in strategyOptions(type)"
              :key="version.id"
              :label="strategyOptionLabel(version)"
              :value="version.id"
            />
          </el-select>
          <span v-if="bindingEnabled && !strategyOptions(type).length" class="kb-binding-empty">
            该类型暂无启用中的版本，请先到策略管理启用
          </span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button class="page-btn-ghost" @click="dialogVisible = false">取消</el-button>
        <el-button class="page-btn-primary" :loading="submitting" @click="handleSubmit">
          保存
        </el-button>
      </template>
    </el-dialog>

    <!-- 导入文档：从页头进入时不预选库，从卡片进入时预选该库 -->
    <ImportDocumentDialog
      v-model="importVisible"
      :preset-kb-id="importKbId"
      @finished="handleImported"
    />
  </div>
</template>

<script setup lang="ts">
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus';
import { computed, onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import {
  addKnowledgeBase,
  deleteKnowledgeBase,
  getKnowledgeBaseDetail,
  getKnowledgeBasePage,
  getKnowledgeBaseStats,
  updateKnowledgeBase,
  updateKnowledgeBaseDisable,
  updateKnowledgeBaseEnable,
} from '@/api/knowledge-base';
import { getStrategyVersions, updateStrategyBindings } from '@/api/strategy';
import KnowledgeBaseCard from '@/components/knowledge-base/KnowledgeBaseCard.vue';
import ImportDocumentDialog from '@/components/knowledge-base/ImportDocumentDialog.vue';
import {
  KB_DESCRIPTION_MAX,
  KB_NAME_MAX,
  KB_STATUS_ACTIVE,
  KB_STATUS_DISABLED,
} from '@/types/knowledge-base';
import type { KnowledgeBase, KnowledgeBaseSort } from '@/types/knowledge-base';
import { STRATEGY_BINDING_TYPES } from '@/types/pipeline';
import { strategyTypeLabel } from '@/types/strategy-config';
import type { StrategyVersion } from '@/types/strategy';

// 知识库页：统计概览与知识库卡片列表

/** 可绑定到知识库的策略类型（三件套；检索不绑 KB，走索引版本的默认检索规则） */
const BINDABLE_STRATEGY_TYPES = STRATEGY_BINDING_TYPES;

const route = useRoute();
const router = useRouter();

/** 导入文档弹窗：importKbId 为空表示不预选知识库 */
const importVisible = ref(false);
const importKbId = ref<string>('');

function openImport(kbId = ''): void {
  importKbId.value = kbId;
  importVisible.value = true;
}

/** 导入结束后刷新列表与统计：文档数变了 */
function handleImported(): void {
  void loadList();
  void loadStats();
}

/*
 * 状态过滤只有两态：**已启用（默认）/ 已停用**。
 *
 * <p>没有「全部」这一档：列表页是日常管理入口，而停用的库不参与接入与检索，
 * 混在默认视图里只会让常用的一屏被停用的库占满。要看停用的切一下即可，
 * 且必须选一态（芯片是"二选一"而不是"可取消的筛选"，避免又回到"等于全部"的第四种含义）。
 */
const statusFilters: { value: number; label: string }[] = [
  { value: KB_STATUS_ACTIVE, label: '已启用' },
  { value: KB_STATUS_DISABLED, label: '已停用' },
];

/**
 * 每页条数：**固定 12，不给选择器**。
 *
 * <p>12 是网格的整除值：2 列 → 6 行、3 列 → 4 行、4 列 → 3 行，末行都不会剩半排空位
 * （10 在这三种列数下都会留空）。面板高度固定、卡片网格内部滚动，条数多少并不改变可见区域，
 * 故不提供"每页条数"选择器 —— 与首页「每页固定 10 条」同一口径。
 */
const PAGE_SIZE = 12;

const kbList = ref<KnowledgeBase[]>([]);
const total = ref(0);
const loading = ref(false);
const selectedStatus = ref<number>(KB_STATUS_ACTIVE);
const keyword = ref('');
const sortBy = ref<KnowledgeBaseSort>('DEFAULT');
const query = ref({ current: 1, size: PAGE_SIZE });

/** 是否偏离了默认视图（默认 = 已启用 + 无关键字）：用于区分「筛选无匹配」与「确实没有启用的库」 */
const isFiltered = computed(
  () => keyword.value.trim() !== '' || selectedStatus.value !== KB_STATUS_ACTIVE,
);

/*
 * 统计条：知识库总数 / 启用中 / 文档总数。
 *
 * <p>三个数**同一可见范围**（后端按当前用户归属过滤）：普通用户只统计自己创建的库，
 * 管理员统计全部。文档数按 wb_file_result 记录数（= 已建档文档数，校验失败的提交不建结果）。
 *
 * 计数是 number：后端全局口径只把「超过 JS 安全整数的雪花 ID」转字符串，计数保持数字
 */
const stats = ref({ knowledgeBaseCount: 0, enabledCount: 0, documentCount: 0 });
const statItems = computed(() => [
  { value: stats.value.knowledgeBaseCount, label: '知识库' },
  { value: stats.value.enabledCount, label: '已启用' },
  { value: stats.value.documentCount, label: '文档数' },
]);

async function loadList(): Promise<void> {
  loading.value = true;
  try {
    const page = await getKnowledgeBasePage({
      current: query.value.current,
      size: query.value.size,
      name: keyword.value.trim() || undefined,
      // 状态总是带值：界面只有启用/停用两态，不存在"不过滤"
      status: selectedStatus.value,
      sort: sortBy.value,
    });
    kbList.value = page.records;
    total.value = page.total;
  } catch {
    // 失败提示已由接口层统一拦截处理
  } finally {
    loading.value = false;
  }
}

async function loadStats(): Promise<void> {
  try {
    stats.value = await getKnowledgeBaseStats();
  } catch {
    // 失败提示已由接口层统一拦截处理
  }
}

/** 查询条件变化后回到第一页，避免停留在越界页码 */
function handleSearch(): void {
  query.value.current = 1;
  void loadList();
}

/** 切换状态过滤后重新查询（只有启用/停用两态，不存在取消选择） */
function handleFilter(value: number): void {
  selectedStatus.value = value;
  handleSearch();
}

/** 切换排序口径后重新查询 */
function handleSort(): void {
  handleSearch();
}

/** 清空筛选：关键字清空、状态回到默认的「已启用」、排序回默认，再查一次 */
function handleClearFilter(): void {
  keyword.value = '';
  selectedStatus.value = KB_STATUS_ACTIVE;
  sortBy.value = 'DEFAULT';
  handleSearch();
}

/** 刷新列表与统计 */
function refresh(): void {
  void loadList();
  void loadStats();
}

// ---------------- 新建 / 编辑 ----------------

const dialogVisible = ref(false);
const submitting = ref(false);
const editingId = ref<string | null>(null);
const dialogFormRef = ref<FormInstance>();

/**
 * 对话框表单。
 *
 * <p>`strategyVersions` 按策略类型存所选版本 ID（三件套）。
 * 「开关开着但还没绑」是允许的中间态：建库时先决定要不要绑定，
 * 具体绑哪个版本要等评测出结果，这里初值可空，只在保存时校验完整性。
 */
const dialogForm = reactive({
  name: '',
  description: '',
  strategyBindingEnabled: 1,
  strategyVersions: {
    PREPROCESS: '',
    CHUNK: '',
    EMBED: '',
  },
});

/** 启用中的策略版本，按类型分组（下拉选项用） */
const strategyVersions = ref<StrategyVersion[]>([]);
const strategyLoading = ref(false);

const isEdit = computed(() => editingId.value !== null);

/**
 * 是否已选择「绑定」。
 *
 * <p>驱动三件套选择器的禁用态与保存校验。模板与提交都要用，提成计算属性，
 * 不在两处各写一遍 `=== 1`。
 */
const bindingEnabled = computed(() => dialogForm.strategyBindingEnabled === 1);

/*
 * 表单校验：长度上限取自 types/knowledge-base.ts 的常量（与后端 DTO 的 @Size 同源口径），
 * 不在模板、规则、提示文案里各写一遍数字 —— 改上限只该改一个地方。
 * 输入框另有 maxlength 硬拦（含字数计数器），规则是兜底（粘贴等路径）。
 */
const dialogRules: FormRules = {
  name: [
    { required: true, message: '请输入知识库名称', trigger: 'blur' },
    { max: KB_NAME_MAX, message: `名称最长 ${KB_NAME_MAX} 字符`, trigger: 'blur' },
  ],
  description: [
    {
      max: KB_DESCRIPTION_MAX,
      message: `业务场景说明最长 ${KB_DESCRIPTION_MAX} 字符`,
      trigger: 'blur',
    },
  ],
};

/** 某类型下可选的启用中版本 */
function strategyOptions(type: string): StrategyVersion[] {
  return strategyVersions.value.filter((item) => item.type === type);
}

/** 下拉选项文案：名称 + 版本号（同名多版本时靠版本号区分） */
function strategyOptionLabel(version: StrategyVersion): string {
  return `${version.name}-${version.version}`;
}

/**
 * 拉取启用中的策略版本（三件套）。
 *
 * <p>列表接口按单个 `type` 查询，按类型并发三次。只取启用中的：
 * 后端绑定接口会拒绝已停用版本，列出来也选不了。
 * 已加载过就跳过，避免每次开弹窗都请求。
 */
async function loadStrategyVersions(): Promise<void> {
  if (strategyVersions.value.length > 0 || strategyLoading.value) {
    return;
  }
  strategyLoading.value = true;
  try {
    const groups = await Promise.all(
      BINDABLE_STRATEGY_TYPES.map((type) => getStrategyVersions(type, false)),
    );
    strategyVersions.value = groups.flat();
  } catch {
    strategyVersions.value = [];
  } finally {
    strategyLoading.value = false;
  }
}

function openCreate(): void {
  editingId.value = null;
  Object.assign(dialogForm, {
    name: '',
    description: '',
    strategyBindingEnabled: 1,
    strategyVersions: { PREPROCESS: '', CHUNK: '', EMBED: '' },
  });
  void loadStrategyVersions();
  dialogVisible.value = true;
}

async function openEdit(kb: KnowledgeBase): Promise<void> {
  editingId.value = kb.id;
  Object.assign(dialogForm, {
    name: kb.name,
    description: kb.description ?? '',
    // 列表已带该字段，先用快照填表避免弹窗空一下
    strategyBindingEnabled: kb.strategyBindingEnabled ?? 1,
    strategyVersions: { PREPROCESS: '', CHUNK: '', EMBED: '' },
  });
  void loadStrategyVersions();
  dialogVisible.value = true;
  try {
    // 再取一次详情：列表快照可能已被他人改动，避免用陈旧值覆盖
    const detail = await getKnowledgeBaseDetail(kb.id);
    Object.assign(dialogForm, {
      name: detail.name,
      description: detail.description ?? '',
      strategyBindingEnabled: detail.strategyBindingEnabled ?? 1,
      // 详情已带三件套的绑定（含 ID 与显示名），直接回填
      strategyVersions: {
        PREPROCESS: detail.preprocessStrategyVersionId ?? '',
        CHUNK: detail.chunkStrategyVersionId ?? '',
        EMBED: detail.embedStrategyVersionId ?? '',
      },
    });
  } catch {
    // 详情取失败时保留列表快照；失败提示已由接口层统一处理
  }
}

/**
 * 保存：基础字段（含策略绑定开关）与三件套绑定分两步提交。
 *
 * <p>两步走：**开关与绑定是两个决定**。建库时先决定"这库要不要走绑定策略"，
 * 具体绑哪个版本要等评测出策略组合的结果，`update` 只改开关；
 * 三件套在开关开启时随本次保存一起提交。
 *
 * <p>开关开启时必须三件套齐全 —— 这是本表单的完整性要求，不是禁止"开了开关还没绑"：
 * 没想好就先关开关，或先开着开关但不打开这个对话框保存。
 */
async function handleSubmit(): Promise<void> {
  const valid = await dialogFormRef.value?.validate().catch(() => false);
  if (!valid) {
    return;
  }
  // 选「绑定」时要求三件套都有值：未填全就拦下，不给后端制造半套绑定
  const bindItems = BINDABLE_STRATEGY_TYPES.map((type) => ({
    strategyType: type,
    strategyVersionId: dialogForm.strategyVersions[type],
  }));
  if (bindingEnabled.value && bindItems.some((item) => !item.strategyVersionId)) {
    const missing = bindItems
      .filter((item) => !item.strategyVersionId)
      .map((item) => strategyTypeLabel(item.strategyType))
      .join(' / ');
    ElMessage.warning(`已选择绑定策略，请为「${missing}」选择版本`);
    return;
  }
  submitting.value = true;
  try {
    const payload = {
      name: dialogForm.name.trim(),
      description: dialogForm.description.trim() || undefined,
      strategyBindingEnabled: dialogForm.strategyBindingEnabled,
    };
    let kbId = editingId.value ?? '';
    if (isEdit.value) {
      await updateKnowledgeBase(kbId, { ...payload, id: kbId });
    } else {
      // 创建接口直接返回新库 ID（字符串）
      kbId = await addKnowledgeBase(payload);
    }
    // 选「绑定」时提交整套绑定（后端要求给全三件套，少一项会 40001）
    if (bindingEnabled.value && kbId) {
      await updateStrategyBindings(kbId, bindItems);
    }
    ElMessage.success(isEdit.value ? '保存成功' : '创建成功');
    dialogVisible.value = false;
    // 新建后回到第一页：列表按 id 倒序，新库在第一页
    if (!isEdit.value) {
      query.value.current = 1;
    }
    void loadList();
    void loadStats();
  } catch {
    // 失败提示已由接口层统一拦截处理
  } finally {
    submitting.value = false;
  }
}

/** 删除知识库：二次确认 → 逻辑删除 → 刷新 */
async function handleDelete(kb: KnowledgeBase): Promise<void> {
  const confirmed = await ElMessageBox.confirm(
    `确认删除知识库「${kb.name}」？删除后该库及其下的文档任务将不可见，且无法恢复`,
    '删除确认',
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
  ).catch(() => false);
  if (!confirmed) {
    return;
  }
  try {
    await deleteKnowledgeBase(kb.id);
    ElMessage.success('删除成功');
    // 删掉当前页最后一条时回退一页，避免停留在空列表（必须在重载前调页）
    if (kbList.value.length === 1 && query.value.current > 1) {
      query.value.current -= 1;
    }
    void loadList();
    void loadStats();
  } catch {
    // 失败提示已由接口层统一拦截处理
  }
}

/**
 * 启用/停用知识库：二次确认 → 调接口 → 刷新列表与统计。
 *
 * <p>停用是「中止服务」而不是「隐藏」：停用后该库拒绝新文件接入与检索
 * （后端 `KnowledgeBaseRules.checkCanSubmit` → KB_NOT_ACTIVE），已发布索引不受影响。
 * 确认文案要把后果说清，不能只说「停用」两个字。
 */
async function handleToggleStatus(kb: KnowledgeBase): Promise<void> {
  const disabling = kb.status === KB_STATUS_ACTIVE;
  const confirmed = await ElMessageBox.confirm(
    disabling
      ? `确认停用知识库「${kb.name}」？停用后将拒绝新文件接入与检索，可随时重新启用`
      : `确认启用知识库「${kb.name}」？启用后可继续提交文档与检索`,
    disabling ? '停用确认' : '启用确认',
    {
      type: 'warning',
      confirmButtonText: disabling ? '停用' : '启用',
      cancelButtonText: '取消',
    },
  ).catch(() => false);
  if (!confirmed) {
    return;
  }
  try {
    if (disabling) {
      await updateKnowledgeBaseDisable(kb.id);
    } else {
      await updateKnowledgeBaseEnable(kb.id);
    }
    /*
     * 提示里点明"已移出当前列表"：状态芯片是二选一的，停用后卡片会立刻从「已启用」视图消失，
     * 不说一句会让人以为库被删了。
     */
    ElMessage.success(disabling ? '已停用，已移出「已启用」列表' : '已启用，已移出「已停用」列表');
    // 该库已不属于当前状态视图：若它是本页最后一条，回退一页再拉，避免停在空列表（与删除同一处理）
    if (kbList.value.length === 1 && query.value.current > 1) {
      query.value.current -= 1;
    }
    void loadList();
    void loadStats();
  } catch {
    // 失败提示已由接口层统一拦截处理
  }
}

function handleDialogClosed(): void {
  dialogFormRef.value?.clearValidate();
  editingId.value = null;
}

onMounted(() => {
  void loadList();
  void loadStats();
  applyEntryAction();
});

/**
 * 响应首页动作卡带来的入口参数（?action=create / ?action=import）。
 *
 * <p>首页的动作卡只是跳到这里，真正的操作在本页完成，避免首页重复实现一遍导入/新建。
 * 处理完立刻把参数从地址栏抹掉，刷新不重复触发。
 */
function applyEntryAction(): void {
  const action = route.query.action;
  if (action !== 'create' && action !== 'import') {
    return;
  }
  if (action === 'create') {
    openCreate();
  } else {
    openImport();
  }
  void router.replace({ path: '/knowledge/manager' });
}
</script>

<style scoped lang="css">
/*
 * 页面骨架全部来自全局 styles/page-shell.css（根元素直接用 .page，含 gap/高度约束，
 * 不在这里重写 —— 重写会出现"外层高度不定 + 内层面板要 flex:1"的矛盾，
 * 面板内部的滚动区就拿不到确定高度）。本页只保留业务样式。
 *
 * 按钮（.page-btn-primary / .page-btn-ghost）、刷新（.page-refresh）、工具条（.page-toolbar）
 * 都在骨架层。
 */

/* 筛选栏内容：搜索 + 状态芯片 + 排序 + 查询/清除 + 刷新，装在共用 .page-toolbar 里 */
.kb-search {
  width: 200px;
}

.kb-filter-group {
  display: flex;
  gap: 6px;
  align-items: center;
}

.kb-filter-label {
  color: var(--kb-text-3);
  font-size: 12px;
}

.kb-sort-select {
  width: 170px;
}

.kb-chip {
  padding: 5px 12px;
  border: 1px solid var(--kb-line);
  border-radius: 99px;
  background: none;
  color: var(--kb-text-2);
  font-size: 12px;
  cursor: pointer;
  transition:
    border-color 0.18s,
    background 0.18s,
    color 0.18s;
}

.kb-chip:hover {
  border-color: var(--kb-line-strong);
  color: var(--kb-text-1);
}

.kb-chip-on {
  border-color: rgb(52 211 153 / 40%);
  background: var(--kb-tint);
  color: var(--kb-primary);
}

/*
 * 卡片滚动区：flex:1 + min-height:0 拿到确定高度，卡片多时在面板内部滚动。
 *
 * 列宽下限 380px：320 时卡片的操作行放不下（被折成两行），
 * 而策略串是标识符（最长 30 字符），也需要更宽才不截断。
 * 上界仍是 1fr —— 窗口宽时卡片跟着变宽，不会留出无用的空白列。
 *
 * **行高必须给下限（minmax(340px, auto)）**：网格项默认 stretch，同一行的虚线卡与
 * 知识库卡会互相拉平 —— 但"列表为空"时那一行**只有虚线卡**，没有参照物，
 * 它就塌回自身高度，同一张卡会出现两种高度。给行高一个定值下限后两种情况都是 340px。
 * 卡片内容比 340px 高时（业务场景说明写得长）行照旧长高，同一行的虚线卡跟着一起长，
 * 不会脱节 —— 用 minmax 而不是写死行高。
 */
.kb-grid {
  display: grid;
  flex: 1;
  gap: 14px;
  align-content: start;
  min-height: 0;
  overflow-y: auto;
  grid-template-columns: repeat(auto-fill, minmax(380px, 1fr));
  grid-auto-rows: minmax(340px, auto);
  padding: 18px;
}

/*
 * 虚线卡：高度**不在这里定**，由上面的网格行高决定（作为网格项 stretch 撑满整行）。
 */
.kb-new {
  display: flex;
  flex-direction: column;
  gap: 10px;
  align-items: center;
  justify-content: center;
  border: 1px dashed var(--kb-line-strong);
  border-radius: var(--kb-radius);
  background: transparent;
  color: var(--kb-text-3);
  cursor: pointer;
  transition:
    border-color 0.2s,
    background 0.2s,
    color 0.2s;
}

.kb-new:hover {
  border-color: rgb(52 211 153 / 50%);
  background: var(--kb-tint);
  color: var(--kb-primary);
}

.kb-new-plus {
  display: grid;
  width: 38px;
  height: 38px;
  place-items: center;
  border: 1px dashed rgb(52 211 153 / 50%);
  border-radius: 50%;
  color: var(--kb-primary);
  font-size: 19px;
}

/*
 * 空态：**不与网格抢高度**（flex: none + 只占自身高度），高度由内容决定。
 *
 * 网格先拿走剩余空间：网格的行高下限是 340px，与空态平分面板高度时装不下那一行，
 * 虚线卡会被裁掉并出现滚动条。
 */
.kb-empty {
  display: flex;
  flex: none;
  flex-direction: column;
  gap: 12px;
  align-items: center;
  justify-content: center;
  padding: 20px 0 24px;
  color: var(--kb-text-3);
  font-size: 13px;
  text-align: center;
}

.kb-empty-text {
  margin: 0;
}

/* 空态里指向虚线卡的提示：比正文弱一档，不抢主文案 */
.kb-empty-hint {
  margin: -6px 0 0;
  color: var(--kb-text-4);
  font-size: 12px;
}

/* ==================== 对话框：策略绑定三件套 ==================== */

/*
 * 「不绑定」时三件套选择器保持可见但禁用它：弹窗高度不随开关切换而跳动
 * （这正是不用 v-if 显隐的原因）。
 * 禁用态的视觉（灰底、not-allowed、不可聚焦）由 Element Plus 自带样式负责，
 * 这里只需撑满宽度。
 */
.kb-binding-select {
  width: 100%;
}

/* 该类型没有启用中的版本：给出可操作的下一步 */
.kb-binding-empty {
  color: var(--kb-warn);
  font-size: 11px;
  line-height: 1.5;
}
</style>
