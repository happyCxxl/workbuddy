<template>
  <div class="model-console">
    <header class="mc-head">
      <div>
        <div class="mc-kicker">{{ kicker }}</div>
        <h1 class="mc-title">{{ title }}</h1>
      </div>
      <div class="mc-stats">
        <div v-for="item in headStats" :key="item.label" class="mc-stat">
          <span class="v" :class="item.tone">{{ item.value }}</span>
          <span class="k">{{ item.label }}</span>
        </div>
      </div>
      <div class="mc-actions">
        <el-button v-if="tab === 'vendors'" type="primary" size="small" @click="openVendorDialog()">
          新增厂商
        </el-button>
        <template v-else-if="tab === 'models'">
          <el-button size="small" :disabled="models.length === 0" @click="testAll">
            批量测试
          </el-button>
          <el-button
            type="primary"
            size="small"
            :disabled="vendors.length === 0"
            @click="openModelDialog()"
          >
            新增模型
          </el-button>
        </template>
        <el-button v-else size="small" @click="loadUsage">刷新</el-button>
      </div>
    </header>

    <template v-if="tab === 'vendors'">
      <div class="mc-toolbar">
        <label class="mc-search">
          <svg width="12" height="12" viewBox="0 0 16 16" fill="none" stroke="currentColor">
            <circle cx="7" cy="7" r="4.4" />
            <path d="M10.4 10.4L14 14" />
          </svg>
          <input v-model="vendorSearch" class="mc-input" placeholder="搜索厂商或接入地址" />
        </label>
        <el-select v-model="vendorStatus" class="mc-select" size="small" placeholder="全部状态">
          <el-option label="全部状态" value="all" />
          <el-option label="启用" value="on" />
          <el-option label="停用" value="off" />
        </el-select>
      </div>

      <div class="mc-panel">
        <el-table class="page-table" :data="filteredVendors" :border="false">
          <el-table-column label="厂商" min-width="120" align="center">
            <template #default="{ row }">
              <span class="mc-id">{{ row.code }}</span>
              <span class="mc-sub">{{ row.display_name }}</span>
            </template>
          </el-table-column>
          <el-table-column label="接入地址" min-width="120" align="center">
            <template #default="{ row }">
              <span class="mc-mono" :title="row.base_url">{{ row.base_url }}</span>
            </template>
          </el-table-column>
          <el-table-column label="密钥" min-width="120" align="center">
            <template #default="{ row }">
              <span class="mc-key-value">{{ row.api_key_masked ?? '未配置' }}</span>
              <span class="mc-led" :class="row.api_key_configured ? 'ok' : 'bad'">
                {{ row.api_key_configured ? '已配置' : '缺失' }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="模型" min-width="120" align="center">
            <template #default="{ row }">{{ vendorModelCount(row.id) }}</template>
          </el-table-column>
          <el-table-column label="状态" min-width="120" align="center">
            <template #default="{ row }">
              <span class="mc-led" :class="row.enabled ? 'ok' : ''">
                {{ row.enabled ? '启用' : '停用' }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="操作" min-width="120" align="center">
            <template #default="{ row }">
              <button class="mc-act" type="button" @click="openVendorDialog(row)">编辑</button>
              <button class="mc-act danger" type="button" @click="removeVendor(row)">删除</button>
            </template>
          </el-table-column>
          <template #empty>
            <div class="page-table-empty">暂无厂商，先新增一个接入地址。</div>
          </template>
        </el-table>
      </div>
    </template>

    <template v-else-if="tab === 'models'">
      <div class="mc-toolbar">
        <label class="mc-search">
          <svg width="12" height="12" viewBox="0 0 16 16" fill="none" stroke="currentColor">
            <circle cx="7" cy="7" r="4.4" />
            <path d="M10.4 10.4L14 14" />
          </svg>
          <input v-model="modelSearch" class="mc-input" placeholder="搜索模型名或标识" />
        </label>
        <el-select v-model="modelVendor" class="mc-select" size="small" placeholder="全部厂商">
          <el-option label="全部厂商" value="all" />
          <el-option
            v-for="vendor in vendors"
            :key="vendor.id"
            :label="vendor.display_name"
            :value="vendor.id"
          />
        </el-select>
        <el-select
          v-model="modelCapability"
          class="mc-select-narrow"
          size="small"
          placeholder="全部能力"
        >
          <el-option label="全部能力" value="all" />
          <el-option
            v-for="item in capabilities"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
        <el-select
          v-model="modelStatus"
          class="mc-select-narrow"
          size="small"
          placeholder="全部状态"
        >
          <el-option label="全部状态" value="all" />
          <el-option label="启用" value="on" />
          <el-option label="停用" value="off" />
        </el-select>
      </div>

      <div class="mc-panel">
        <el-table class="page-table" :data="filteredModels" :border="false">
          <el-table-column label="模型" min-width="120" align="center">
            <template #default="{ row }">
              <span class="mc-id">{{ row.display_name }}</span>
              <span v-if="row.model_key !== row.display_name" class="mc-sub">
                {{ row.model_key }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="厂商" min-width="120" align="center">
            <template #default="{ row }">
              <span class="mc-chip">{{ vendorCode(row.vendor_id) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="能力" min-width="120" align="center">
            <template #default="{ row }">
              <span class="mc-chip" :class="chipClass(row.capability)">{{ row.capability }}</span>
            </template>
          </el-table-column>
          <el-table-column label="规格" min-width="120" align="center">
            <template #default="{ row }">
              <span class="mc-mono">{{ specs(row) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" min-width="120" align="center">
            <template #default="{ row }">
              <span class="mc-led" :class="modelLed(row).tone">{{ modelLed(row).label }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" min-width="120" align="center">
            <template #default="{ row }">
              <button class="mc-act" type="button" @click="runTest(row)">测试</button>
              <button class="mc-act" type="button" @click="openModelDialog(row)">编辑</button>
              <button class="mc-act danger" type="button" @click="removeModel(row)">删除</button>
            </template>
          </el-table-column>
          <template #empty>
            <div class="page-table-empty">暂无模型，先选择一个厂商录入。</div>
          </template>
        </el-table>
      </div>
    </template>

    <template v-else>
      <div class="mc-kpis">
        <div class="mc-kpi">
          <div class="v accent">{{ totalCalls }}</div>
          <div class="k">CALLS</div>
          <div class="d">当前分组统计</div>
        </div>
        <div class="mc-kpi">
          <div class="v">{{ compactTokens(totalTokens) }}</div>
          <div class="k">TOKENS</div>
          <div class="d">输入与输出合计</div>
        </div>
        <div class="mc-kpi">
          <div class="v" :class="{ warn: failureRate > 0 }">{{ failureRate }}%</div>
          <div class="k">FAILURE</div>
          <div class="d">{{ totalFailures }} 次失败</div>
        </div>
        <div class="mc-kpi">
          <div class="v">{{ avgLatency }}<span class="mc-mono">ms</span></div>
          <div class="k">AVG LATENCY</div>
          <div class="d">按调用次数加权</div>
        </div>
      </div>

      <div class="mc-toolbar">
        <el-select v-model="groupBy" class="mc-select" size="small" @change="loadUsage">
          <el-option label="按来源" value="source" />
          <el-option label="按模型" value="model_id" />
          <el-option label="按能力" value="capability" />
        </el-select>
      </div>

      <div class="mc-panel">
        <el-table class="page-table" :data="usage" :border="false">
          <el-table-column label="分组" min-width="120" align="center">
            <template #default="{ row }">{{ row.key ?? '未知' }}</template>
          </el-table-column>
          <el-table-column label="调用" min-width="120" align="center">
            <template #default="{ row }">{{ row.calls }}</template>
          </el-table-column>
          <el-table-column label="Token" min-width="120" align="center">
            <template #default="{ row }">
              {{ compactTokens(row.tokens) }}
              <span class="mc-bar" :style="{ '--w': tokenWidth(row.tokens) }"></span>
            </template>
          </el-table-column>
          <el-table-column label="平均延迟" min-width="120" align="center">
            <template #default="{ row }">{{ row.avg_latency_ms }} ms</template>
          </el-table-column>
          <el-table-column label="失败" min-width="120" align="center">
            <template #default="{ row }">
              <span :class="{ 'mc-fail': row.failures > 0 }">{{ row.failures }}</span>
            </template>
          </el-table-column>
          <template #empty>
            <div class="page-table-empty">暂无调用记录。</div>
          </template>
        </el-table>
      </div>
    </template>

    <el-dialog
      v-model="vendorDialog"
      :title="vendorForm.id ? '编辑厂商' : '新增厂商'"
      width="520px"
    >
      <el-form class="mc-dialog-form" label-width="90px">
        <el-form-item label="编码">
          <el-input v-model="vendorForm.code" :disabled="Boolean(vendorForm.id)" />
        </el-form-item>
        <el-form-item label="名称"><el-input v-model="vendorForm.display_name" /></el-form-item>
        <el-form-item label="接入地址"><el-input v-model="vendorForm.base_url" /></el-form-item>
        <el-form-item label="密钥">
          <el-input
            v-model="apiKeyInput"
            type="password"
            show-password
            :placeholder="vendorForm.id ? '留空保持不变' : '请输入 API Key'"
          />
        </el-form-item>
        <el-form-item label="启用"><el-switch v-model="vendorForm.enabled" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="vendorDialog = false">取消</el-button>
        <el-button type="primary" @click="saveVendor">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="modelDialog" :title="modelForm.id ? '编辑模型' : '新增模型'" width="600px">
      <el-form class="mc-dialog-form" label-width="90px">
        <el-form-item label="厂商">
          <el-select v-model="modelForm.vendor_id" :disabled="Boolean(modelForm.id)">
            <el-option
              v-for="vendor in vendors"
              :key="vendor.id"
              :label="vendor.display_name"
              :value="vendor.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="能力">
          <el-select v-model="modelForm.capability" :disabled="Boolean(modelForm.id)">
            <el-option
              v-for="item in capabilities"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="模型名"><el-input v-model="modelForm.model_key" /></el-form-item>
        <el-form-item label="显示名"><el-input v-model="modelForm.display_name" /></el-form-item>
        <el-form-item v-if="modelForm.capability === 'EMBEDDING'" label="维度">
          <el-input-number v-model="modelForm.dimension" :min="1" />
        </el-form-item>
        <el-form-item label="上下文">
          <el-input-number v-model="modelForm.context_window" :min="0" />
        </el-form-item>
        <el-form-item label="单批上限">
          <el-input-number v-model="modelForm.batch_limit" :min="0" />
        </el-form-item>
        <el-form-item label="RPM">
          <el-input-number v-model="modelForm.rpm_limit" :min="0" />
        </el-form-item>
        <el-form-item label="TPM">
          <el-input-number v-model="modelForm.tpm_limit" :min="0" />
        </el-form-item>
        <el-form-item label="输入价">
          <el-input-number v-model="modelForm.price_in" :min="0" :precision="6" />
        </el-form-item>
        <el-form-item label="输出价">
          <el-input-number v-model="modelForm.price_out" :min="0" :precision="6" />
        </el-form-item>
        <el-form-item label="币种">
          <el-select v-model="modelForm.currency">
            <el-option label="CNY" value="CNY" />
            <el-option label="USD" value="USD" />
          </el-select>
        </el-form-item>
        <el-form-item label="特性">
          <el-checkbox-group v-model="featureList">
            <el-checkbox label="stream" />
            <el-checkbox label="tools" />
            <el-checkbox label="json_object" />
            <el-checkbox label="json_schema" />
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="查询前缀">
          <el-input v-model="queryPrefix" placeholder="仅向量模型的查询指令前缀" />
        </el-form-item>
        <el-form-item label="启用"><el-switch v-model="modelForm.enabled" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="modelDialog = false">取消</el-button>
        <el-button type="primary" @click="saveModel">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus';
import { computed, onMounted, reactive, ref, watch } from 'vue';

import {
  addModel,
  addVendor,
  deleteModel,
  deleteVendor,
  getModelTest,
  getModels,
  getUsage,
  getVendors,
  updateModel,
  updateVendor,
  type GatewayModel,
  type ModelVendor,
  type UsageRow,
} from '@/api/model-admin';

const props = defineProps<{ tab: string }>();

const vendors = ref<ModelVendor[]>([]);
const models = ref<GatewayModel[]>([]);
const usage = ref<UsageRow[]>([]);
const groupBy = ref('source');

const vendorSearch = ref('');
const vendorStatus = ref('all');
const modelSearch = ref('');
const modelVendor = ref('all');
const modelCapability = ref('all');
const modelStatus = ref('all');

const capabilities = [
  { value: 'CHAT', label: '对话' },
  { value: 'EMBEDDING', label: '向量化' },
  { value: 'OCR', label: '文字识别' },
  { value: 'RERANK', label: '重排' },
];

const kicker = computed(() => {
  if (props.tab === 'vendors') return 'MODEL / VENDORS';
  if (props.tab === 'models') return 'MODEL / CATALOG';
  return 'MODEL / USAGE';
});

const title = computed(() => {
  if (props.tab === 'vendors') return '厂商';
  if (props.tab === 'models') return '模型';
  return '用量';
});

const degradedCount = computed(
  () =>
    models.value.filter(
      (m) => m.enabled && m.status !== 'UNKNOWN' && m.status !== 'HEALTHY' && m.status !== 'DOWN',
    ).length,
);
const downCount = computed(() => models.value.filter((m) => m.status === 'DOWN').length);
const totalCalls = computed(() => usage.value.reduce((sum, row) => sum + row.calls, 0));
const totalTokens = computed(() => usage.value.reduce((sum, row) => sum + row.tokens, 0));
const totalFailures = computed(() => usage.value.reduce((sum, row) => sum + row.failures, 0));
const failureRate = computed(() =>
  totalCalls.value === 0 ? 0 : Number(((totalFailures.value / totalCalls.value) * 100).toFixed(1)),
);
const avgLatency = computed(() => {
  if (totalCalls.value === 0) return 0;
  const weighted = usage.value.reduce((sum, row) => sum + row.avg_latency_ms * row.calls, 0);
  return Math.round(weighted / totalCalls.value);
});

const headStats = computed(() => {
  if (props.tab === 'vendors') {
    return [
      { label: 'VENDORS', value: vendors.value.length, tone: '' },
      { label: 'MODELS', value: models.value.length, tone: '' },
      { label: 'ENABLED', value: models.value.filter((m) => m.enabled).length, tone: 'accent' },
      { label: 'DEGRADED', value: degradedCount.value, tone: 'warn' },
    ];
  }
  if (props.tab === 'models') {
    return [
      { label: 'TOTAL', value: models.value.length, tone: '' },
      {
        label: 'ONLINE',
        value: models.value.filter((m) => m.enabled && m.status !== 'DOWN').length,
        tone: 'accent',
      },
      { label: 'DEGRADED', value: degradedCount.value, tone: 'warn' },
      { label: 'DOWN', value: downCount.value, tone: 'bad' },
    ];
  }
  return [
    {
      label: 'GROUP BY',
      value: groupBy.value === 'source' ? '来源' : groupBy.value === 'model_id' ? '模型' : '能力',
      tone: '',
    },
    { label: 'CALLS', value: totalCalls.value, tone: 'accent' },
    { label: 'TOKENS', value: compactTokens(totalTokens.value), tone: '' },
  ];
});

const filteredVendors = computed(() => {
  const text = vendorSearch.value.trim().toLowerCase();
  return vendors.value.filter((vendor) => {
    if (vendorStatus.value === 'on' && !vendor.enabled) return false;
    if (vendorStatus.value === 'off' && vendor.enabled) return false;
    if (!text) return true;
    return (
      vendor.code.toLowerCase().includes(text) ||
      vendor.display_name.toLowerCase().includes(text) ||
      vendor.base_url.toLowerCase().includes(text)
    );
  });
});

const filteredModels = computed(() => {
  const text = modelSearch.value.trim().toLowerCase();
  return models.value.filter((model) => {
    if (modelVendor.value !== 'all' && model.vendor_id !== modelVendor.value) return false;
    if (modelCapability.value !== 'all' && model.capability !== modelCapability.value) return false;
    if (modelStatus.value === 'on' && !model.enabled) return false;
    if (modelStatus.value === 'off' && model.enabled) return false;
    if (!text) return true;
    return (
      model.display_name.toLowerCase().includes(text) ||
      model.model_key.toLowerCase().includes(text)
    );
  });
});

function compactTokens(value: number): string {
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(1)}M`;
  if (value >= 1_000) return `${(value / 1_000).toFixed(1)}K`;
  return String(value);
}

function tokenWidth(value: number): string {
  if (totalTokens.value === 0) return '0';
  return `${Math.max(2, Math.round((value / totalTokens.value) * 100))}%`;
}

function vendorCode(vendorId: string): string {
  return vendors.value.find((vendor) => vendor.id === vendorId)?.code ?? vendorId;
}

function vendorModelCount(vendorId: string): number {
  return models.value.filter((model) => model.vendor_id === vendorId).length;
}

function chipClass(capability: string): string {
  return capability.toLowerCase();
}

function specs(raw: unknown): string {
  const model = raw as GatewayModel;
  const parts: string[] = [];
  if (model.context_window) parts.push(`ctx ${compactTokens(model.context_window)}`);
  if (model.dimension) parts.push(`${model.dimension} dim`);
  if (model.batch_limit) parts.push(`batch ${model.batch_limit}`);
  if (model.rpm_limit) parts.push(`rpm ${compactTokens(model.rpm_limit)}`);
  return parts.length > 0 ? parts.join(' · ') : '—';
}

function modelLed(raw: unknown): { tone: string; label: string } {
  const model = raw as GatewayModel;
  if (!model.enabled) return { tone: '', label: '停用' };
  if (model.status === 'DOWN') return { tone: 'bad', label: '不可用' };
  if (model.status === 'DEGRADED') return { tone: 'warn', label: '延迟偏高' };
  return { tone: 'ok', label: '正常' };
}

async function loadVendors(): Promise<void> {
  vendors.value = await getVendors();
}

async function loadModels(): Promise<void> {
  models.value = await getModels();
}

async function loadUsage(): Promise<void> {
  usage.value = await getUsage(groupBy.value);
}

const vendorDialog = ref(false);
const apiKeyInput = ref('');
const vendorForm = reactive<Partial<ModelVendor>>({
  id: undefined,
  code: '',
  display_name: '',
  base_url: '',
  enabled: true,
});

const modelDialog = ref(false);
const featureList = ref<string[]>([]);
const queryPrefix = ref('');
const modelForm = reactive<Partial<GatewayModel>>({
  id: undefined,
  vendor_id: '',
  model_key: '',
  display_name: '',
  capability: 'CHAT',
  dimension: 1024,
  context_window: null,
  batch_limit: null,
  rpm_limit: null,
  tpm_limit: null,
  price_in: null,
  price_out: null,
  currency: 'CNY',
  enabled: true,
});

function openVendorDialog(raw?: unknown): void {
  const row = raw as ModelVendor | undefined;
  Object.assign(vendorForm, {
    id: row?.id,
    code: row?.code ?? '',
    display_name: row?.display_name ?? '',
    base_url: row?.base_url ?? '',
    enabled: row?.enabled ?? true,
  });
  apiKeyInput.value = '';
  vendorDialog.value = true;
}

async function saveVendor(): Promise<void> {
  const payload = { ...vendorForm, api_key: apiKeyInput.value || undefined };
  if (vendorForm.id) {
    await updateVendor(vendorForm.id, payload);
  } else {
    await addVendor(payload);
  }
  vendorDialog.value = false;
  ElMessage.success('已保存');
  await loadVendors();
}

async function removeVendor(raw: unknown): Promise<void> {
  const row = raw as ModelVendor;
  await ElMessageBox.confirm(`删除厂商 ${row.display_name}？`, '确认', { type: 'warning' });
  await deleteVendor(row.id);
  ElMessage.success('已删除');
  await loadVendors();
}

function openModelDialog(raw?: unknown): void {
  const row = raw as GatewayModel | undefined;
  Object.assign(modelForm, {
    id: row?.id,
    vendor_id: row?.vendor_id ?? (vendors.value.length > 0 ? vendors.value[0].id : ''),
    model_key: row?.model_key ?? '',
    display_name: row?.display_name ?? '',
    capability: row?.capability ?? 'CHAT',
    dimension: row?.dimension ?? 1024,
    context_window: row?.context_window ?? null,
    batch_limit: row?.batch_limit ?? null,
    rpm_limit: row?.rpm_limit ?? null,
    tpm_limit: row?.tpm_limit ?? null,
    price_in: row?.price_in ?? null,
    price_out: row?.price_out ?? null,
    currency: row?.currency ?? 'CNY',
    enabled: row?.enabled ?? true,
  });
  featureList.value = Object.entries(row?.features ?? {})
    .filter(([, enabled]) => enabled)
    .map(([key]) => key);
  queryPrefix.value = String(row?.features['query_prefix'] ?? '');
  modelDialog.value = true;
}

async function saveModel(): Promise<void> {
  const features: Record<string, boolean | string> = {};
  for (const key of featureList.value) {
    features[key] = true;
  }
  if (queryPrefix.value.trim()) {
    features['query_prefix'] = queryPrefix.value.trim();
  }
  const payload = { ...modelForm, features };
  if (modelForm.id) {
    await updateModel(modelForm.id, payload);
  } else {
    await addModel(payload);
  }
  modelDialog.value = false;
  ElMessage.success('已保存');
  await loadModels();
}

async function removeModel(raw: unknown): Promise<void> {
  const row = raw as GatewayModel;
  await ElMessageBox.confirm(`删除模型 ${row.display_name}？`, '确认', { type: 'warning' });
  await deleteModel(row.id);
  ElMessage.success('已删除');
  await loadModels();
}

/** 厂商错误码 → 用户可读原因（原始报错只留接口层供排查，不上页面） */
const PROVIDER_REASON: Record<string, string> = {
  PROVIDER_AUTH: '鉴权失败，请检查 API Key',
  PROVIDER_UNAVAILABLE: '服务不可达（超时/网络/限流）',
  PROVIDER_BAD_REQUEST: '请求被模型拒绝（参数或输入错误）',
  PROVIDER_ERROR: '模型服务异常',
};

async function runTest(raw: unknown): Promise<void> {
  const row = raw as GatewayModel;
  const result = await getModelTest(row.id);
  if (result.ok) {
    ElMessage.success(`${row.display_name} 连通正常`);
  } else {
    const reasons = [
      ...new Set(
        (result.attempts ?? []).map((item) => PROVIDER_REASON[item.code ?? ''] ?? '模型返回错误'),
      ),
    ];
    ElMessage.error(
      `${row.display_name} 连通失败：${result.message ?? '未知原因'}` +
        (reasons.length ? `（${reasons.join('；')}）` : ''),
    );
  }
  await loadModels();
}

async function testAll(): Promise<void> {
  if (models.value.length === 0) {
    return;
  }
  for (const model of models.value.slice(0, 5)) {
    await getModelTest(model.id).catch(() => undefined);
  }
  ElMessage.success('已完成前 5 个模型的连通测试');
  await loadModels();
}

onMounted(async () => {
  await loadVendors();
  await loadModels();
  if (props.tab === 'usage') {
    await loadUsage();
  }
});

watch(
  () => props.tab,
  async (tab) => {
    if (tab === 'usage' && usage.value.length === 0) {
      await loadUsage();
    }
  },
);
</script>

<style scoped>
.mc-fail {
  color: var(--kb-warn);
}
</style>
