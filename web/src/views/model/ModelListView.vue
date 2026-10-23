<template>
  <div class="page">
    <div class="page-head">
      <!-- 标题与说明要包一层：.page-head 是 flex 行，不包会与右侧按钮并排 -->
      <div>
        <h1 class="page-title">模型清单</h1>
        <p class="page-desc">维护可用模型与价格限额，以及启用状态</p>
      </div>
      <div class="page-actions">
        <el-button class="page-btn-primary" @click="openCreate"> 新增模型 </el-button>
      </div>
    </div>

    <div class="page-panel">
      <div class="page-toolbar">
        <el-select v-model="capabilityFilter" class="model-select" placeholder="能力">
          <el-option label="全部能力" value="" />
          <el-option
            v-for="item in MODEL_CAPABILITIES"
            :key="item"
            :label="MODEL_CAPABILITY_LABELS[item]"
            :value="item"
          />
        </el-select>
        <el-select v-model="statusFilter" class="model-select" placeholder="状态">
          <el-option label="全部状态" value="" />
          <el-option label="启用" :value="MODEL_STATUS.ENABLED" />
          <el-option label="停用" :value="MODEL_STATUS.DISABLED" />
        </el-select>
        <el-input
          v-model="keyword"
          class="model-search"
          placeholder="搜索模型名"
          clearable
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />
        <el-button class="page-btn-primary" @click="handleSearch">查询</el-button>
        <el-button class="page-btn-ghost" @click="handleReset">重置</el-button>
        <button
          class="page-refresh"
          type="button"
          title="刷新"
          :disabled="loading"
          @click="loadModels"
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

      <!-- 固定高度滚动区：高度由祖先链的确定高度决定，不随记录数变化 -->
      <div class="page-table-body">
        <!-- 列宽与对齐口径见 styles/page-shell.css 的「列表表格」一节：8 列同 min-width 即等宽 -->
        <el-table
          v-loading="loading"
          class="page-table"
          height="100%"
          :data="models"
          :border="false"
        >
          <el-table-column label="厂商" min-width="160" align="center">
            <template #default="{ row }">
              <div>{{ row.vendorName }}</div>
              <div class="model-muted model-mono">{{ row.vendorCode }}</div>
            </template>
          </el-table-column>
          <el-table-column label="模型" min-width="160" align="center">
            <template #default="{ row }">
              <div>{{ row.modelKey }}</div>
            </template>
          </el-table-column>
          <el-table-column label="能力" min-width="160" align="center">
            <template #default="{ row }">
              <span v-for="item in row.capabilities" :key="item" class="model-tag">{{ item }}</span>
            </template>
          </el-table-column>
          <el-table-column label="长度（输入/输出/上下文）" min-width="160" align="center">
            <template #default="{ row }">
              <span class="model-mono">{{ lengthText(row as ModelItem) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="价格（未命中/命中/输出）" min-width="160" align="center">
            <template #default="{ row }">
              <span class="model-mono">{{ priceText(row as ModelItem) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="限额（RPM / TPM）" min-width="160" align="center">
            <template #default="{ row }">
              <span class="model-mono">{{ limitText(row as ModelItem) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" min-width="160" align="center">
            <template #default="{ row }">
              <el-tag
                :type="row.status === MODEL_STATUS.ENABLED ? 'success' : 'info'"
                effect="dark"
                size="small"
              >
                {{ row.status === MODEL_STATUS.ENABLED ? '启用' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" min-width="160" align="center" fixed="right">
            <!-- 插槽里的 row 是 el-table 的 DefaultRow，交给强类型函数时逐个断言成 ModelItem -->
            <template #default="{ row }">
              <el-button
                class="model-row-btn"
                link
                type="primary"
                @click="openEdit(row as ModelItem)"
              >
                编辑
              </el-button>
              <el-button
                class="model-row-btn"
                link
                :type="row.status === MODEL_STATUS.ENABLED ? 'danger' : 'success'"
                :loading="togglingId === row.id"
                @click="handleToggleStatus(row as ModelItem)"
              >
                {{ row.status === MODEL_STATUS.ENABLED ? '停用' : '启用' }}
              </el-button>
            </template>
          </el-table-column>
          <template #empty>
            <div class="page-table-empty">{{ loading ? '加载中…' : '暂无模型' }}</div>
          </template>
        </el-table>
      </div>

      <div class="page-panel-foot">
        <span>共 {{ total }} 条</span>
        <el-pagination
          class="model-pager"
          layout="prev, pager, next"
          :total="total"
          :page-size="query.size"
          :current-page="query.current"
          @current-change="handlePageChange"
        />
      </div>
    </div>

    <!-- 新增 / 编辑：同一抽屉；厂商选项来自前端枚举，抽屉里没有密钥与地址字段 -->
    <el-drawer
      v-model="drawerVisible"
      class="model-drawer"
      :title="drawerTitle"
      size="720px"
      destroy-on-close
    >
      <el-form
        ref="formRef"
        :model="form"
        :rules="formRules"
        label-position="top"
        :hide-required-asterisk="true"
      >
        <div class="model-form-group-head">
          <span class="model-form-group-title">基础信息</span>
        </div>
        <div class="model-form-row model-form-row-3">
          <el-form-item prop="vendorCode" label="厂商" class="model-form-item">
            <el-select
              v-model="form.vendorCode"
              class="model-form-control"
              placeholder="请选择厂商"
            >
              <el-option
                v-for="vendor in MODEL_VENDORS"
                :key="vendor.code"
                :label="`${vendor.displayName}（${vendor.code}）`"
                :value="vendor.code"
              />
            </el-select>
          </el-form-item>
          <el-form-item prop="modelKey" label="模型名" class="model-form-item">
            <el-input v-model="form.modelKey" class="model-form-control" placeholder="qwen-max" />
          </el-form-item>
          <el-form-item prop="thinkingEnabled" label="支持思考模式" class="model-form-item">
            <el-switch
              v-model="form.thinkingEnabled"
              class="model-form-control"
              :active-value="1"
              :inactive-value="0"
            />
          </el-form-item>
        </div>
        <el-form-item prop="capabilities" label="能力" class="model-form-item">
          <!-- 收起超额标签：全选五项时保持单行高度，不把表单撑开 -->
          <el-select
            v-model="form.capabilities"
            class="model-form-control"
            multiple
            collapse-tags
            collapse-tags-tooltip
            :max-collapse-tags="3"
            placeholder="至少选一项"
          >
            <el-option
              v-for="item in MODEL_CAPABILITIES"
              :key="item"
              :label="`${item} · ${MODEL_CAPABILITY_LABELS[item]}`"
              :value="item"
            />
          </el-select>
        </el-form-item>

        <div class="model-form-group-head">
          <span class="model-form-group-title">长度</span>
        </div>
        <div class="model-form-row model-form-row-3">
          <el-form-item prop="inputLimit" label="最大输入长度" class="model-form-item">
            <div class="model-limit-row">
              <el-input-number
                v-model="form.inputLimit"
                class="model-form-control"
                :min="0"
                :controls="false"
                placeholder="可留空"
              />
              <el-select v-model="inputUnit" class="model-limit-unit">
                <el-option label="K" value="K" />
                <el-option label="M" value="M" />
              </el-select>
            </div>
          </el-form-item>
          <el-form-item prop="outputLimit" label="最大输出长度" class="model-form-item">
            <div class="model-limit-row">
              <el-input-number
                v-model="form.outputLimit"
                class="model-form-control"
                :min="0"
                :controls="false"
                placeholder="可留空"
              />
              <el-select v-model="outputUnit" class="model-limit-unit">
                <el-option label="K" value="K" />
                <el-option label="M" value="M" />
              </el-select>
            </div>
          </el-form-item>
          <el-form-item prop="contextWindow" label="上下文长度" class="model-form-item">
            <div class="model-limit-row">
              <el-input-number
                v-model="form.contextWindow"
                class="model-form-control"
                :min="0"
                :controls="false"
                placeholder="可留空"
              />
              <el-select v-model="contextUnit" class="model-limit-unit">
                <el-option label="K" value="K" />
                <el-option label="M" value="M" />
              </el-select>
            </div>
          </el-form-item>
        </div>
        <!-- 思考模式两行只在开关打开时出现：关掉后提交不带上，库里留空 -->
        <div v-if="form.thinkingEnabled === 1" class="model-form-row">
          <el-form-item
            prop="thinkingInputLimit"
            label="最大输入长度（思考模式下）"
            class="model-form-item"
          >
            <div class="model-limit-row">
              <el-input-number
                v-model="form.thinkingInputLimit"
                class="model-form-control"
                :min="0"
                :controls="false"
                placeholder="可留空"
              />
              <el-select v-model="thinkingInputUnit" class="model-limit-unit">
                <el-option label="K" value="K" />
                <el-option label="M" value="M" />
              </el-select>
            </div>
          </el-form-item>
          <el-form-item
            prop="thinkingOutputLimit"
            label="最大输出长度（思考模式下）"
            class="model-form-item"
          >
            <div class="model-limit-row">
              <el-input-number
                v-model="form.thinkingOutputLimit"
                class="model-form-control"
                :min="0"
                :controls="false"
                placeholder="可留空"
              />
              <el-select v-model="thinkingOutputUnit" class="model-limit-unit">
                <el-option label="K" value="K" />
                <el-option label="M" value="M" />
              </el-select>
            </div>
          </el-form-item>
        </div>

        <div class="model-form-group-head">
          <span class="model-form-group-title">价格</span>
          <span class="model-form-group-sub">每百万 token</span>
        </div>
        <div class="model-form-row model-form-row-3">
          <el-form-item prop="priceInPerM" label="输入·缓存未命中" class="model-form-item">
            <el-input-number
              v-model="form.priceInPerM"
              class="model-form-control"
              :min="0"
              :controls="false"
              placeholder="未登记"
            />
          </el-form-item>
          <el-form-item prop="priceInPerMCacheHit" label="输入·缓存命中" class="model-form-item">
            <el-input-number
              v-model="form.priceInPerMCacheHit"
              class="model-form-control"
              :min="0"
              :controls="false"
              placeholder="未登记"
            />
          </el-form-item>
          <el-form-item prop="priceOutPerM" label="输出" class="model-form-item">
            <el-input-number
              v-model="form.priceOutPerM"
              class="model-form-control"
              :min="0"
              :controls="false"
              placeholder="未登记"
            />
          </el-form-item>
        </div>

        <div class="model-form-group-head">
          <span class="model-form-group-title">限额与状态</span>
        </div>
        <div class="model-form-row model-form-row-3">
          <el-form-item prop="rpmLimit" label="RPM" class="model-form-item">
            <el-input-number
              v-model="form.rpmLimit"
              class="model-form-control"
              :min="1"
              :controls="false"
              placeholder="必填"
            />
          </el-form-item>
          <el-form-item prop="tpmLimit" label="TPM" class="model-form-item">
            <el-input-number
              v-model="form.tpmLimit"
              class="model-form-control"
              :min="1"
              :controls="false"
              placeholder="必填"
            />
          </el-form-item>
          <el-form-item prop="currency" label="币种" class="model-form-item">
            <el-select v-model="form.currency" class="model-form-control">
              <el-option v-for="item in MODEL_CURRENCIES" :key="item" :label="item" :value="item" />
            </el-select>
          </el-form-item>
        </div>
      </el-form>

      <template #footer>
        <el-button class="page-btn-ghost" @click="drawerVisible = false">取消</el-button>
        <el-button class="page-btn-primary" :loading="submitting" @click="handleSubmit">
          保存
        </el-button>
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus';
import { computed, onMounted, reactive, ref } from 'vue';

import { addModel, getModelPage, updateModel, updateModelStatus } from '@/api/model';
import {
  MODEL_CAPABILITIES,
  MODEL_CAPABILITY_LABELS,
  MODEL_CURRENCIES,
  MODEL_STATUS,
  MODEL_VENDORS,
  type ModelItem,
  type ModelSavePayload,
} from '@/types/model';

// 模型清单页：模型目录维护（新增 / 编辑 / 启停）；接口层已统一处理失败提示

const capabilityFilter = ref('');
const statusFilter = ref('');
const keyword = ref('');
const models = ref<ModelItem[]>([]);
const total = ref(0);
const query = reactive({ current: 1, size: 10 });
const loading = ref(false);
const togglingId = ref<string | null>(null);

const drawerVisible = ref(false);
const submitting = ref(false);
const editingId = ref<string | null>(null);
const formRef = ref<FormInstance>();

/** 抽屉表单的值形状：数值列可留空（null），长度按「数值 + 单位」成对出现 */
interface ModelForm {
  vendorCode: string;
  modelKey: string;
  capabilities: string[];
  thinkingEnabled: number;
  inputLimit: number | null;
  outputLimit: number | null;
  thinkingInputLimit: number | null;
  thinkingOutputLimit: number | null;
  contextWindow: number | null;
  priceInPerM: number | null;
  priceInPerMCacheHit: number | null;
  priceOutPerM: number | null;
  currency: string;
  rpmLimit: number | null;
  tpmLimit: number | null;
}

const form = reactive<ModelForm>({
  vendorCode: '',
  modelKey: '',
  capabilities: [],
  thinkingEnabled: 0,
  inputLimit: null,
  outputLimit: null,
  thinkingInputLimit: null,
  thinkingOutputLimit: null,
  contextWindow: null,
  priceInPerM: null,
  priceInPerMCacheHit: null,
  priceOutPerM: null,
  currency: 'CNY',
  rpmLimit: null,
  tpmLimit: null,
});

/** 非负校验规则：长度、价格与限额这些数值列都不接受负数 */
const NON_NEGATIVE_RULE = {
  type: 'number' as const,
  min: 0,
  message: '不能为负数',
  trigger: 'change' as const,
};

/**
 * 长度单位：K = 1000 tokens、M = 1000000 tokens（十进制，与厂商文档口径一致）。
 *
 * 库里存的是整数 token，单位只活在表单里：提交前按单位乘上去，编辑时按整除关系拆回来。
 */
type LengthUnit = 'K' | 'M';

const UNIT_FACTOR: Record<LengthUnit, number> = { K: 1000, M: 1000000 };

/** 把整数 token 拆成「数值 + 单位」：能整除 M 用 M，否则能整除 K 用 K，都不能则原样按 K 的数值展示 */
function splitLength(tokens: number | null): { value: number | null; unit: LengthUnit } {
  if (tokens === null) {
    return { value: null, unit: 'K' };
  }
  if (tokens > 0 && tokens % UNIT_FACTOR.M === 0) {
    return { value: tokens / UNIT_FACTOR.M, unit: 'M' };
  }
  if (tokens > 0 && tokens % UNIT_FACTOR.K === 0) {
    return { value: tokens / UNIT_FACTOR.K, unit: 'K' };
  }
  return { value: tokens, unit: 'K' };
}

/** 长度展示：能整除就用 M / K 后缀，否则原样给 token 数字 */
function lengthCellText(tokens: number | null): string {
  if (tokens === null) {
    return '—';
  }
  if (tokens > 0 && tokens % UNIT_FACTOR.M === 0) {
    return `${tokens / UNIT_FACTOR.M}M`;
  }
  if (tokens > 0 && tokens % UNIT_FACTOR.K === 0) {
    return `${tokens / UNIT_FACTOR.K}K`;
  }
  return String(tokens);
}

const inputUnit = ref<LengthUnit>('K');
const outputUnit = ref<LengthUnit>('K');
const contextUnit = ref<LengthUnit>('K');
const thinkingInputUnit = ref<LengthUnit>('K');
const thinkingOutputUnit = ref<LengthUnit>('K');

const formRules: FormRules = {
  vendorCode: [{ required: true, message: '请选择厂商', trigger: 'change' }],
  modelKey: [
    { required: true, message: '请输入模型名', trigger: 'blur' },
    { max: 128, message: '模型名最长 128 字符', trigger: 'blur' },
  ],
  capabilities: [
    { type: 'array', required: true, message: '至少选择一项能力', trigger: 'change' },
    {
      validator: (_rule, value: string[], callback: (error?: Error) => void) => {
        callback(value.length === 0 ? new Error('至少选择一项能力') : undefined);
      },
      trigger: 'change',
    },
  ],
  inputLimit: [NON_NEGATIVE_RULE],
  outputLimit: [NON_NEGATIVE_RULE],
  thinkingInputLimit: [NON_NEGATIVE_RULE],
  thinkingOutputLimit: [NON_NEGATIVE_RULE],
  contextWindow: [NON_NEGATIVE_RULE],
  priceInPerM: [NON_NEGATIVE_RULE],
  priceInPerMCacheHit: [NON_NEGATIVE_RULE],
  priceOutPerM: [NON_NEGATIVE_RULE],
  rpmLimit: [NON_NEGATIVE_RULE, { required: true, message: '请输入 RPM 上限', trigger: 'blur' }],
  tpmLimit: [NON_NEGATIVE_RULE, { required: true, message: '请输入 TPM 上限', trigger: 'blur' }],
};

const drawerTitle = computed(() =>
  editingId.value === null ? '新增模型' : `编辑模型 · ${form.modelKey}`,
);

/** 金额展示：最多六位小数，去掉末尾的零；空值读作 — */
function moneyText(value: number | null): string {
  if (value === null) {
    return '—';
  }
  const text = Number(value).toFixed(6).replace(/0+$/, '').replace(/\.$/, '');
  return text === '' ? '0' : text;
}

/** 价格列文案：输入按「缓存未命中 / 缓存命中」两档，再接输出，末尾给「币种/百万 token」 */
function priceText(item: ModelItem): string {
  const prices = [item.priceInPerM, item.priceInPerMCacheHit, item.priceOutPerM]
    .map(moneyText)
    .join(' / ');
  return `${prices} ${item.currency}/M`;
}

/** 长度列文案：最大输入 / 最大输出 / 上下文长度，走 K / M 后缀，任一为空读作 — */
function lengthText(item: ModelItem): string {
  return [item.inputLimit, item.outputLimit, item.contextWindow].map(lengthCellText).join(' / ');
}

/** 限额列文案：两项都可留空，留空读作 — */
function limitText(item: ModelItem): string {
  const rpm = item.rpmLimit === null ? '—' : String(item.rpmLimit);
  const tpm = item.tpmLimit === null ? '—' : String(item.tpmLimit);
  return `${rpm} / ${tpm}`;
}

/** 非负兜底：表单控件已限最小值，提交前再扫一遍数值列 */
function firstNegativeField(): string | null {
  const values: [string, number | null][] = [
    ['最大输入长度', form.inputLimit],
    ['最大输出长度', form.outputLimit],
    ['思考模式最大输入长度', form.thinkingInputLimit],
    ['思考模式最大输出长度', form.thinkingOutputLimit],
    ['上下文长度', form.contextWindow],
    ['输入单价（缓存未命中）', form.priceInPerM],
    ['输入单价（缓存命中）', form.priceInPerMCacheHit],
    ['输出单价', form.priceOutPerM],
    ['RPM 上限', form.rpmLimit],
    ['TPM 上限', form.tpmLimit],
  ];
  for (const [label, value] of values) {
    if (value !== null && value < 0) {
      return `${label}不能为负数`;
    }
  }
  return null;
}

async function loadModels(): Promise<void> {
  loading.value = true;
  try {
    const page = await getModelPage({
      current: query.current,
      size: query.size,
      capability: capabilityFilter.value === '' ? undefined : capabilityFilter.value,
      status: statusFilter.value === '' ? undefined : statusFilter.value,
      keyword: keyword.value.trim() === '' ? undefined : keyword.value.trim(),
    });
    models.value = page.records;
    total.value = page.total;
  } catch {
    // 失败提示已由接口层统一拦截
  } finally {
    loading.value = false;
  }
}

/** 带筛选条件的查询：回到第一页再取数 */
function handleSearch(): void {
  query.current = 1;
  void loadModels();
}

/** 翻页：按当前筛选条件取目标页 */
function handlePageChange(page: number): void {
  query.current = page;
  void loadModels();
}

/** 重置全部筛选条件并重新查询 */
function handleReset(): void {
  capabilityFilter.value = '';
  statusFilter.value = '';
  keyword.value = '';
  handleSearch();
}

/** 新增时默认选中枚举里的第一个厂商 */
function defaultVendorCode(): string {
  return MODEL_VENDORS[0].code;
}

/** 表单回到新增口径的初值 */
function resetForm(): void {
  form.vendorCode = defaultVendorCode();
  form.modelKey = '';
  form.capabilities = [];
  form.thinkingEnabled = 0;
  form.inputLimit = null;
  form.outputLimit = null;
  form.thinkingInputLimit = null;
  form.thinkingOutputLimit = null;
  form.contextWindow = null;
  inputUnit.value = 'K';
  outputUnit.value = 'K';
  contextUnit.value = 'K';
  thinkingInputUnit.value = 'K';
  thinkingOutputUnit.value = 'K';
  form.priceInPerM = null;
  form.priceInPerMCacheHit = null;
  form.priceOutPerM = null;
  form.currency = MODEL_CURRENCIES[0];
  form.rpmLimit = null;
  form.tpmLimit = null;
}

function openCreate(): void {
  editingId.value = null;
  resetForm();
  drawerVisible.value = true;
}

/** 编辑：把行上的值铺进表单，厂商也可改（仍只能选配置里已有的厂商） */
function openEdit(item: ModelItem): void {
  editingId.value = item.id;
  form.vendorCode = item.vendorCode;
  form.modelKey = item.modelKey;
  form.capabilities = [...item.capabilities];
  form.thinkingEnabled = item.thinkingEnabled;
  const thinkingInput = splitLength(item.thinkingInputLimit);
  const thinkingOutput = splitLength(item.thinkingOutputLimit);
  const input = splitLength(item.inputLimit);
  const output = splitLength(item.outputLimit);
  const context = splitLength(item.contextWindow);
  form.inputLimit = input.value;
  form.outputLimit = output.value;
  form.thinkingInputLimit = thinkingInput.value;
  form.thinkingOutputLimit = thinkingOutput.value;
  form.contextWindow = context.value;
  inputUnit.value = input.unit;
  outputUnit.value = output.unit;
  thinkingInputUnit.value = thinkingInput.unit;
  thinkingOutputUnit.value = thinkingOutput.unit;
  contextUnit.value = context.unit;
  form.priceInPerM = item.priceInPerM;
  form.priceInPerMCacheHit = item.priceInPerMCacheHit;
  form.priceOutPerM = item.priceOutPerM;
  form.currency = item.currency;
  form.rpmLimit = item.rpmLimit;
  form.tpmLimit = item.tpmLimit;
  drawerVisible.value = true;
}

/** 表单里的「数值 + 单位」合成整数 token；数值留空即该列留空 */
function toTokens(value: number | null, unit: LengthUnit): number | null {
  return value === null ? null : value * UNIT_FACTOR[unit];
}

/** 表单 → 请求体：长度按所选单位换算成 token，思考模式关闭时两列长度不下发（置 null），文本列空白按 null 下发 */
function buildPayload(): ModelSavePayload {
  const thinking = form.thinkingEnabled === 1;
  return {
    vendorCode: form.vendorCode,
    modelKey: form.modelKey.trim(),
    capabilities: [...form.capabilities],
    thinkingEnabled: form.thinkingEnabled,
    inputLimit: toTokens(form.inputLimit, inputUnit.value),
    outputLimit: toTokens(form.outputLimit, outputUnit.value),
    thinkingInputLimit: thinking
      ? toTokens(form.thinkingInputLimit, thinkingInputUnit.value)
      : null,
    thinkingOutputLimit: thinking
      ? toTokens(form.thinkingOutputLimit, thinkingOutputUnit.value)
      : null,
    contextWindow: toTokens(form.contextWindow, contextUnit.value),
    priceInPerM: form.priceInPerM,
    priceInPerMCacheHit: form.priceInPerMCacheHit,
    priceOutPerM: form.priceOutPerM,
    currency: form.currency,
    rpmLimit: form.rpmLimit,
    tpmLimit: form.tpmLimit,
  };
}

async function handleSubmit(): Promise<void> {
  const formEl = formRef.value;
  if (formEl === undefined) {
    return;
  }
  const valid = await formEl.validate().catch(() => false);
  if (!valid) {
    return;
  }
  const negative = firstNegativeField();
  if (negative !== null) {
    ElMessage.error(negative);
    return;
  }
  submitting.value = true;
  try {
    if (editingId.value === null) {
      await addModel(buildPayload());
      ElMessage.success('已新增模型');
    } else {
      await updateModel(editingId.value, buildPayload());
      ElMessage.success('已保存模型');
    }
    drawerVisible.value = false;
    await loadModels();
  } catch {
    // 失败提示已由接口层统一拦截
  } finally {
    submitting.value = false;
  }
}

/** 启停：目标状态取反，成功后刷新清单 */
async function handleToggleStatus(item: ModelItem): Promise<void> {
  const enabled = item.status === MODEL_STATUS.ENABLED;
  const confirmed = await ElMessageBox.confirm(
    enabled
      ? `确认停用模型「${item.vendorName} / ${item.modelKey}」？停用后该模型不再参与路由与调用，可随时重新启用`
      : `确认启用模型「${item.vendorName} / ${item.modelKey}」？启用后该模型重新进入可用清单`,
    enabled ? '停用确认' : '启用确认',
    {
      type: 'warning',
      confirmButtonText: enabled ? '停用' : '启用',
      cancelButtonText: '取消',
    },
  ).catch(() => false);
  if (!confirmed) {
    return;
  }
  togglingId.value = item.id;
  try {
    await updateModelStatus(item.id, enabled ? MODEL_STATUS.DISABLED : MODEL_STATUS.ENABLED);
    ElMessage.success(enabled ? '已停用模型' : '已启用模型');
    await loadModels();
  } catch {
    // 失败提示已由接口层统一拦截
  } finally {
    togglingId.value = null;
  }
}

onMounted(async () => {
  await loadModels();
});
</script>

<style scoped lang="css">
/*
 * 页面骨架用全局 styles/page-shell.css 的 .page / .page-head / .page-title / .page-desc /
 * .page-panel / .page-toolbar / .page-table-body / .page-panel-foot；本页只补工具条控件宽度、
 * 表格文本样式与抽屉表单的私有类。
 */

/* ==================== 工具条控件宽度 ==================== */
.model-select {
  width: 150px;
}

.model-search {
  width: 200px;
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

/* 能力标签：描边小标签，多个标签之间留一点间距 */
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

.model-row-btn {
  padding: 0 5px;
  font-size: 12px;
}

/* ==================== 抽屉表单 ==================== */
.model-form-item {
  margin-bottom: 14px;
}

/* 控件撑满表单项：抽屉宽度固定，逐项单列铺开 */
.model-form-control {
  width: 100%;
}

/* 长度这类「数值 + 单位」的复合控件：两个控件同行，单位窄一档 */
.model-limit-row {
  display: flex;
  gap: 6px;
  align-items: center;
  width: 100%;
}

.model-limit-row .model-form-control {
  flex: 1;
  width: auto;
  min-width: 0;
}

.model-limit-unit {
  flex: none;
  width: 62px;
}

/* 抽屉宽度：字段行最多三列，720px 下单格约 215px，长标签与数值都放得下 */
.model-drawer {
  min-width: 720px;
}

/* 字段行：列数由 --model-cols 给（抽屉 720px 时三列每格约 215px、两列约 334px） */
.model-form-row {
  --model-cols: 2;

  display: grid;
  gap: 12px;
  grid-template-columns: repeat(var(--model-cols), minmax(0, 1fr));
}

.model-form-row-3 {
  --model-cols: 3;
}

/* 三列时数值框仍有约 160px，单位按需占位 */
.model-form-row-3 .model-limit-unit {
  width: 56px;
}

/* 分组分隔：标题与说明同一行，右侧一条横线把段落分开 */
.model-form-group-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 22px 0 14px;
}

.model-form-group-title {
  flex: none;
  color: var(--kb-text-1);
  font-size: 12px;
  font-weight: 600;
}

.model-form-group-sub {
  flex: none;
  color: var(--kb-text-3);
  font-size: 11px;
}

.model-form-group-head::after {
  content: '';
  flex: 1;
  height: 1px;
  background: var(--kb-line);
}
</style>
