<template>
  <div class="gateway-page page-shell">
    <div class="page-panel">
      <el-tabs v-model="activeTab" class="gateway-tabs">
        <el-tab-pane label="厂商" name="vendors">
          <div class="gateway-toolbar">
            <el-button type="primary" @click="openVendorDialog()">新增厂商</el-button>
          </div>
          <el-table :data="vendors" size="small" class="page-table">
            <el-table-column prop="code" label="编码" min-width="160" align="center" />
            <el-table-column prop="display_name" label="名称" min-width="160" align="center" />
            <el-table-column prop="base_url" label="接入地址" min-width="160" align="center" />
            <el-table-column prop="api_key_env" label="密钥变量" min-width="160" align="center" />
            <el-table-column label="状态" min-width="160" align="center">
              <template #default="{ row }">
                <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
                  {{ row.enabled ? '启用' : '停用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" min-width="160" align="center">
              <template #default="{ row }">
                <el-button link type="primary" @click="openVendorDialog(row)">编辑</el-button>
                <el-button link type="danger" @click="removeVendor(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="模型" name="models">
          <div class="gateway-toolbar">
            <el-button type="primary" :disabled="vendors.length === 0" @click="openModelDialog()">
              新增模型
            </el-button>
            <el-select
              v-model="capabilityFilter"
              clearable
              placeholder="全部能力"
              class="gateway-filter"
            >
              <el-option
                v-for="item in capabilities"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </div>
          <el-table :data="filteredModels" size="small" class="page-table">
            <el-table-column prop="display_name" label="模型" min-width="160" align="center" />
            <el-table-column prop="model_key" label="模型名" min-width="160" align="center" />
            <el-table-column prop="capability" label="能力" min-width="160" align="center" />
            <el-table-column label="厂商" min-width="160" align="center">
              <template #default="{ row }">{{ vendorName(row.vendor_id) }}</template>
            </el-table-column>
            <el-table-column prop="dimension" label="维度" min-width="160" align="center" />
            <el-table-column label="状态" min-width="160" align="center">
              <template #default="{ row }">
                <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
                  {{ row.enabled ? '启用' : '停用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" min-width="160" align="center">
              <template #default="{ row }">
                <el-button link type="primary" @click="openModelDialog(row)">编辑</el-button>
                <el-button link type="primary" @click="runTest(row)">测试</el-button>
                <el-button link type="danger" @click="removeModel(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="用量" name="usage">
          <div class="gateway-toolbar">
            <el-select v-model="groupBy" class="gateway-filter" @change="loadUsage">
              <el-option label="按来源" value="source" />
              <el-option label="按模型" value="model_id" />
              <el-option label="按能力" value="capability" />
            </el-select>
          </div>
          <el-table :data="usage" size="small" class="page-table">
            <el-table-column prop="key" label="分组" min-width="160" align="center" />
            <el-table-column prop="calls" label="调用次数" min-width="160" align="center" />
            <el-table-column prop="tokens" label="Token" min-width="160" align="center" />
            <el-table-column
              prop="avg_latency_ms"
              label="平均延迟(ms)"
              min-width="160"
              align="center"
            />
            <el-table-column prop="failures" label="失败" min-width="160" align="center" />
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </div>

    <el-dialog
      v-model="vendorDialog"
      :title="vendorForm.id ? '编辑厂商' : '新增厂商'"
      width="520px"
    >
      <el-form label-width="90px">
        <el-form-item label="编码">
          <el-input v-model="vendorForm.code" :disabled="Boolean(vendorForm.id)" />
        </el-form-item>
        <el-form-item label="名称"><el-input v-model="vendorForm.display_name" /></el-form-item>
        <el-form-item label="接入地址"><el-input v-model="vendorForm.base_url" /></el-form-item>
        <el-form-item label="密钥变量"><el-input v-model="vendorForm.api_key_env" /></el-form-item>
        <el-form-item label="启用"><el-switch v-model="vendorForm.enabled" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="vendorDialog = false">取消</el-button>
        <el-button type="primary" @click="saveVendor">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="modelDialog" :title="modelForm.id ? '编辑模型' : '新增模型'" width="560px">
      <el-form label-width="90px">
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
            <el-checkbox label="response_format" />
            <el-checkbox label="structured_output" />
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
import { computed, onMounted, reactive, ref } from 'vue';

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

const activeTab = ref('vendors');
const vendors = ref<ModelVendor[]>([]);
const models = ref<GatewayModel[]>([]);
const usage = ref<UsageRow[]>([]);
const capabilityFilter = ref<string | null>(null);
const groupBy = ref('source');

const capabilities = [
  { value: 'CHAT', label: '对话' },
  { value: 'EMBEDDING', label: '向量化' },
  { value: 'OCR', label: '文字识别' },
  { value: 'RERANK', label: '重排' },
];

const vendorDialog = ref(false);
const vendorForm = reactive<Partial<ModelVendor>>({
  id: undefined,
  code: '',
  display_name: '',
  base_url: '',
  api_key_env: '',
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

const filteredModels = computed(() =>
  capabilityFilter.value
    ? models.value.filter((model) => model.capability === capabilityFilter.value)
    : models.value,
);

function vendorName(vendorId: string): string {
  return vendors.value.find((vendor) => vendor.id === vendorId)?.display_name ?? vendorId;
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

function openVendorDialog(raw?: unknown): void {
  const row = raw as ModelVendor | undefined;
  Object.assign(vendorForm, {
    id: row?.id,
    code: row?.code ?? '',
    display_name: row?.display_name ?? '',
    base_url: row?.base_url ?? '',
    api_key_env: row?.api_key_env ?? '',
    enabled: row?.enabled ?? true,
  });
  vendorDialog.value = true;
}

async function saveVendor(): Promise<void> {
  if (vendorForm.id) {
    await updateVendor(vendorForm.id, vendorForm);
  } else {
    await addVendor(vendorForm);
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

async function runTest(raw: unknown): Promise<void> {
  const row = raw as GatewayModel;
  const result = await getModelTest(row.id);
  if (result.ok) {
    ElMessage.success('连通正常');
  } else {
    ElMessage.error(result.message ?? '连通失败');
  }
  await loadModels();
}

onMounted(async () => {
  await loadVendors();
  await loadModels();
  await loadUsage();
});
</script>

<style scoped>
.gateway-page {
  padding: 12px;
}

.gateway-toolbar {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-bottom: 10px;
}

.gateway-filter {
  width: 160px;
}
</style>
