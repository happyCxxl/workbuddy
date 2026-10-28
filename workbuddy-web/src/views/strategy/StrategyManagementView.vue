<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h1 class="page-title">策略管理</h1>
        <p class="page-desc">管理预处理 / 切片 / 向量化 / 检索策略的版本与知识库绑定</p>
      </div>
      <div class="page-actions">
        <el-button size="small" @click="toggleShowInactive">
          {{ showInactive ? '隐藏已停用' : '显示已停用' }}
        </el-button>
        <el-button size="small" type="primary" @click="openCreate">+ 新建策略</el-button>
      </div>
    </div>

    <!--
      两栏工作台装进 .page-panel：与知识库的卡片区、用户管理的表格区是同一层容器。
    -->
    <div class="page-panel">
      <div class="stg-body">
        <!-- ==================== 左：类型 + 策略族 + 版本 ==================== -->
        <aside class="stg-rail">
          <div class="stg-rail-types">
            <button
              v-for="type in STRATEGY_TYPES"
              :key="type"
              class="stg-type-btn"
              :class="{ 'is-on': type === activeType }"
              @click="switchType(type)"
            >
              {{ STRATEGY_TYPE_SHORT_LABELS[type] }}
            </button>
          </div>

          <div class="stg-rail-body">
            <div v-if="loading" class="stg-rail-empty">加载中…</div>
            <div v-else-if="families.length === 0" class="stg-rail-empty">
              该类型还没有策略{{ showInactive ? '' : '（可能有已停用的，试试「显示已停用」）' }}
            </div>

            <div v-for="family in families" v-else :key="family.name" class="stg-family">
              <button class="stg-family-head" @click="toggleFamily(family.name)">
                <span class="stg-caret" :class="{ 'is-open': isFamilyOpen(family.name) }"></span>
                <span class="stg-family-name">{{ family.name }}</span>
                <span class="stg-family-dots">
                  <i
                    v-for="item in family.versions"
                    :key="item.id"
                    class="stg-dot"
                    :class="{ 'is-live': isStrategyActive(item) }"
                  ></i>
                </span>
              </button>

              <div v-if="isFamilyOpen(family.name)" class="stg-family-vers">
                <button
                  v-for="item in family.versions"
                  :key="item.id"
                  class="stg-ver"
                  :class="{ 'is-on': item.id === selectedId, 'is-off': !isStrategyActive(item) }"
                  @click="select(item)"
                >
                  <span class="stg-ver-v">{{ item.version }}</span>
                  <span class="stg-ver-s">
                    {{ isStrategyActive(item) ? '启用中' : '已停用' }}
                    <template v-if="boundKbIds.has(item.id)"> · 已绑定</template>
                  </span>
                </button>
              </div>
            </div>
          </div>
        </aside>

        <!-- ==================== 右：详情 / 编辑 ==================== -->
        <section class="stg-pane">
          <div v-if="!selected" class="stg-pane-empty">
            <span>从左侧选择一个策略版本查看配置</span>
            <span class="stg-pane-empty-hint">或点右上角「+ 新建策略」</span>
          </div>

          <template v-else>
            <div class="stg-pane-top">
              <div class="stg-pane-title">
                <span class="stg-pane-name">
                  {{ selected.name }}
                  <span class="stg-pane-ver">{{ selected.version }}</span>
                </span>
                <span class="stg-tag" :class="isStrategyActive(selected) ? 'is-on' : 'is-off'">
                  <i class="stg-tag-dot"></i>{{ isStrategyActive(selected) ? '启用中' : '已停用' }}
                </span>
                <span class="page-spacer"></span>

                <div class="stg-pane-ops">
                  <template v-if="!editing">
                    <el-button size="small" @click="startCopy">复制为新版本</el-button>
                    <el-button size="small" @click="toggleActive">
                      {{ isStrategyActive(selected) ? '停用' : '启用' }}
                    </el-button>
                    <el-button size="small" @click="removeVersion">删除</el-button>
                  </template>
                  <template v-else>
                    <el-button size="small" @click="cancelEdit">取消</el-button>
                    <el-button size="small" type="primary" :loading="saving" @click="saveVersion">
                      保存为新版本
                    </el-button>
                  </template>
                </div>
              </div>

              <div class="stg-pane-meta">
                <span class="stg-blood">
                  <span class="stg-blood-label">版本血缘</span>
                  <span v-for="(node, index) in lineageNodes" :key="node.key">
                    <span v-if="index > 0" class="stg-blood-arrow">→</span>
                    <span
                      class="stg-blood-node"
                      :class="{ 'is-on': node.current, 'is-next': node.next }"
                    >
                      {{ node.text }}
                    </span>
                  </span>
                </span>
                <span class="stg-meta-sep">|</span>
                <span>{{ strategyTypeLabel(selected.type) }}</span>
                <span class="stg-meta-sep">·</span>
                <span>{{ formatTime(selected.createTime) }}</span>
                <span class="stg-meta-sep">·</span>
                <span class="stg-mono">id {{ selected.id }}</span>
              </div>
            </div>

            <div class="stg-pane-scroll">
              <div class="stg-sec">
                <span class="stg-sec-name">{{ editing ? '新版本配置' : '配置' }}</span>
                <i class="stg-help" :title="STRATEGY_TYPE_DESCRIPTIONS[activeType]">?</i>
              </div>

              <!-- 编辑态先给名称与版本号 -->
              <div v-if="editing" class="stg-name-row">
                <label class="stg-field">
                  <span class="stg-field-label">策略名</span>
                  <el-input v-model="draft.name" size="small" class="stg-mono-input" />
                </label>
                <label class="stg-field">
                  <span class="stg-field-label">版本号</span>
                  <el-input v-model="draft.version" size="small" class="stg-mono-input" />
                </label>
                <label class="stg-field">
                  <span class="stg-field-label">类型</span>
                  <el-input :model-value="strategyTypeLabel(activeType)" size="small" disabled />
                </label>
              </div>

              <div v-if="configBroken" class="stg-warn">
                该版本的配置无法解析为 JSON，可能是脏数据。保存会以空配置覆盖，请谨慎操作。
              </div>

              <PreprocessForm
                v-if="activeType === 'PREPROCESS'"
                v-model:config="preprocessConfig"
                :readonly="!editing"
              />
              <ChunkForm
                v-else-if="activeType === 'CHUNK'"
                v-model:config="chunkConfig"
                :readonly="!editing"
              />
              <EmbedForm
                v-else-if="activeType === 'EMBED'"
                v-model:config="embedConfig"
                :readonly="!editing"
              />
              <RetrievalForm
                v-else-if="activeType === 'RETRIEVAL'"
                v-model:config="retrievalConfig"
                :readonly="!editing"
              />
              <div v-else class="stg-warn">未知策略类型「{{ activeType }}」，无法编辑配置。</div>

              <!-- 知识库绑定：检索类策略不参与绑定（后端口径） -->
              <template v-if="bindingSupported">
                <div class="stg-sec">
                  <span class="stg-sec-name">知识库绑定</span>
                  <i class="stg-help" :title="BINDING_HINT">?</i>
                  <span class="page-spacer"></span>
                </div>
                <div v-if="bindingLoading" class="stg-bind-empty">加载中…</div>
                <div v-else class="stg-binds">
                  <div v-for="kb in knowledgeBases" :key="kb.id" class="stg-bind">
                    <i class="stg-bind-dot" :class="{ 'is-on': boundKbIds.has(kb.id) }"></i>
                    <span class="stg-bind-name">{{ kb.name }}</span>
                    <span class="stg-bind-state">{{
                      boundKbIds.has(kb.id) ? '已绑定' : '未绑定'
                    }}</span>
                    <el-button
                      v-if="boundKbIds.has(kb.id)"
                      size="small"
                      text
                      @click="unbind(kb.id)"
                    >
                      解绑
                    </el-button>
                    <el-button v-else size="small" text @click="bind(kb.id)">绑定此策略</el-button>
                  </div>
                </div>
              </template>
            </div>
          </template>
        </section>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 策略管理页（对齐设计样例 B2）。
 *
 * <p>左栏按「类型 → 策略族 → 版本」三级组织；右栏是选中版本的配置与绑定。
 * 三处后端约束在这里落地：
 *
 * <ol>
 *   <li>**策略版本不可修改**：编辑一律走「复制为新版本」，保存后生成新行，旧行原样保留
 *       （后端 `PUT` 的语义就是复制，返回新行）；</li>
 *   <li>**唯一键 `(type,name,version)`**：复制时默认把版本号 +1，撞键后端报 40001；</li>
 *   <li>**被知识库绑定的版本不能删**：后端报 40452，这里捕获后引导改用「停用」。</li>
 * </ol>
 */
import { computed, onMounted, reactive, ref, watch } from 'vue';
import type { WritableComputedRef } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { useRoute, useRouter } from 'vue-router';

import {
  addStrategyVersion,
  deleteStrategyVersion,
  getStrategyBindings,
  getStrategyVersions,
  updateStrategyBinding,
  updateStrategyVersion,
  updateStrategyVersionDisable,
  updateStrategyVersionEnable,
} from '@/api/strategy';
import { getKnowledgeBasePage } from '@/api/knowledge-base';
import ChunkForm from '@/components/strategy/ChunkForm.vue';
import EmbedForm from '@/components/strategy/EmbedForm.vue';
import PreprocessForm from '@/components/strategy/PreprocessForm.vue';
import RetrievalForm from '@/components/strategy/RetrievalForm.vue';
import { STRATEGY_BINDING_TYPES } from '@/types/pipeline';
import {
  STRATEGY_TYPES,
  STRATEGY_TYPE_DESCRIPTIONS,
  STRATEGY_TYPE_SHORT_LABELS,
  isStrategyActive,
  parseConfigByType,
  strategyTypeLabel,
} from '@/types/strategy-config';
import type {
  ChunkConfig,
  EmbedConfig,
  PreprocessConfig,
  RetrievalConfig,
  StrategyType,
} from '@/types/strategy-config';
import type { StrategyVersion } from '@/types/strategy';

/** 策略管理页一次拉全量（版本数量在数十条量级，不分页） */
const LIST_SIZE = 200;

/** 后端错误码：撞唯一键 */
const CODE_PARAM_INVALID = 40001;
/** 后端错误码：策略被知识库绑定，禁止删除 */
const CODE_BOUND_FORBIDDEN = 40452;

const BINDING_HINT =
  '绑定后，对该知识库触发本环节且未显式指定策略时用这个版本；绑定失效（停用 / 被删）会自动回退到「最新启用」并告警';

const route = useRoute();
const router = useRouter();

const activeType = ref<StrategyType>(toStrategyType(route.query.type));
const versions = ref<StrategyVersion[]>([]);
const loading = ref(false);
const showInactive = ref(true);
const selectedId = ref('');
const editing = ref(false);
const saving = ref(false);
const draft = reactive({ name: '', version: '' });

/** 编辑态的配置副本：改完点保存才提交，取消即丢弃 */
const draftSnapshot = ref<string | null>(null);
const openFamilies = ref<Set<string>>(new Set());

interface KnowledgeBaseBrief {
  id: string;
  name: string;
}

const knowledgeBases = ref<KnowledgeBaseBrief[]>([]);
const boundKbIds = ref<Set<string>>(new Set());
const bindingLoading = ref(false);

function toStrategyType(value: unknown): StrategyType {
  const raw = String(value ?? '');
  return (STRATEGY_TYPES as readonly string[]).includes(raw) ? (raw as StrategyType) : 'PREPROCESS';
}

// ============================================================
// 列表与分组
// ============================================================

/** 策略族：同名策略的各版本收成一组，避免 v1/v2 散落在别的策略之间 */
interface StrategyFamily {
  name: string;
  versions: StrategyVersion[];
}

const families = computed<StrategyFamily[]>(() => {
  const byName = new Map<string, StrategyVersion[]>();
  for (const item of versions.value) {
    const list = byName.get(item.name) ?? [];
    list.push(item);
    byName.set(item.name, list);
  }
  return [...byName.entries()].map(([name, list]) => ({
    name,
    // 版本号倒序：新的在前，与"版本列表 新→旧"的惯常口径一致
    versions: [...list].sort((a, b) =>
      b.version.localeCompare(a.version, undefined, { numeric: true }),
    ),
  }));
});

const selected = computed(
  () => versions.value.find((item) => item.id === selectedId.value) ?? null,
);

/** 检索类策略不参与知识库绑定：后端绑定接口只支持 PREPROCESS/CHUNK/EMBED */
const bindingSupported = computed(() =>
  (STRATEGY_BINDING_TYPES as readonly string[]).includes(activeType.value),
);

function isFamilyOpen(name: string): boolean {
  return openFamilies.value.has(name);
}

function toggleFamily(name: string): void {
  const next = new Set(openFamilies.value);
  if (next.has(name)) {
    next.delete(name);
  } else {
    next.add(name);
  }
  openFamilies.value = next;
}

/** 选中某策略族时自动展开它 */
function openFamilyOf(name: string): void {
  if (!openFamilies.value.has(name)) {
    openFamilies.value = new Set([...openFamilies.value, name]);
  }
}

// ============================================================
// 血缘与时间
// ============================================================

/**
 * 版本血缘节点。
 *
 * <p>后端把「编辑」实现为复制新行，同族各版本之间**没有显式的父子指针**，
 * 血缘只能按版本号推导：当前查看的版本 → 复制后会生成的下一个版本号。
 */
const lineageNodes = computed(() => {
  if (!selected.value) {
    return [];
  }
  const siblings =
    families.value.find((item) => item.name === selected.value?.name)?.versions ?? [];
  const others = siblings.filter((item) => item.id !== selected.value?.id);
  const nodes = others
    .slice()
    .reverse()
    .map((item) => ({ key: item.id, text: item.version, current: false, next: false }));
  nodes.push({ key: selected.value.id, text: selected.value.version, current: true, next: false });
  if (editing.value) {
    nodes.push({ key: 'draft', text: `${draft.version} · 待保存`, current: false, next: true });
  }
  return nodes;
});

/** 版本号 +1：v1 → v2；无法识别数字时退回 v1 */
function nextVersion(current: string): string {
  const matched = /^(.*?)(\d+)$/.exec(current.trim());
  if (!matched) {
    return 'v1';
  }
  return `${matched[1]}${Number(matched[2]) + 1}`;
}

function formatTime(value: string | null): string {
  if (!value) {
    return '—';
  }
  return value.replace('T', ' ').slice(0, 16);
}

// ============================================================
// 配置对象（表单就地修改的就是它）
// ============================================================

/** 编辑态用草案快照，查看态用选中版本的快照 */
const activeSnapshot = computed<string | null>(() =>
  editing.value ? draftSnapshot.value : (selected.value?.configSnapshot ?? null),
);

const configBroken = computed(() => {
  const snapshot = activeSnapshot.value;
  if (!snapshot || snapshot.trim() === '') {
    return false;
  }
  try {
    JSON.parse(snapshot);
    return false;
  } catch {
    return true;
  }
});

/**
 * 表单持有的配置对象：**每个快照只解析一次**并缓存。
 *
 * <p>两个要点：
 * <ol>
 *   <li>不能写成「每次都重新解析」的 computed——那样表单的就地修改会被立刻丢掉；</li>
 *   <li>也不能用 `ref().value = 新对象` 整体替换：表单用 `v-model:config` 绑的是
 *       **这个对象本身**，整体替换会让表单继续改旧对象，而父组件读的是新对象，
 *       两边脱钩（保存出来的配置会丢掉所有编辑）。这里只清空/填充同一个对象。</li>
 * </ol>
 */
const configObject = reactive<Record<string, unknown>>({});
const configKey = ref('');

function syncConfigObject(): void {
  const key = `${activeType.value}:${selectedId.value}:${editing.value ? 'edit' : 'view'}`;
  if (key === configKey.value) {
    return;
  }
  configKey.value = key;
  const parsed = parseConfigByType(activeType.value, activeSnapshot.value);
  for (const existing of Object.keys(configObject)) {
    delete configObject[existing];
  }
  Object.assign(configObject, parsed);
}

watch(activeSnapshot, syncConfigObject, { immediate: true });

/**
 * 各类型的配置视图：**带 setter 的可写 computed**。
 *
 * <p>`v-model:config` 在子组件内部会调用 `emit('update:config', value)`，
 * 只读 computed 会让这次写回抛错。setter 里就地合并，保证父组件与表单操作的是同一份数据。
 */
function applyConfig(next: Record<string, unknown>): void {
  for (const existing of Object.keys(configObject)) {
    delete configObject[existing];
  }
  Object.assign(configObject, next);
}

/**
 * 按类型收口的可写 computed 工厂。
 *
 * <p>类型断言只在这里出现一次：配置对象本身是 `Record<string, unknown>`
 * （不同策略类型的结构不同），交给对应表单时按该类型的接口确认一次即可。
 * 若把断言散落到模板里，每处都得写，且容易漏。
 */
function configModel<T extends object>(): WritableComputedRef<T> {
  return computed<T>({
    get: () => configObject as T,
    // 表单回写的是同一份配置对象（v-model 语义），这里只做一次键合并
    set: (value: T) => applyConfig(value as Record<string, unknown>),
  });
}

const preprocessConfig = configModel<PreprocessConfig>();
const chunkConfig = configModel<ChunkConfig>();
const embedConfig = configModel<EmbedConfig>();
const retrievalConfig = configModel<RetrievalConfig>();

// ============================================================
// 数据加载
// ============================================================

async function loadVersions(): Promise<void> {
  loading.value = true;
  try {
    versions.value = await getStrategyVersions(activeType.value, showInactive.value);
    // 选中项失效（切类型、或该版本被隐藏）时回落到第一个
    if (!versions.value.some((item) => item.id === selectedId.value)) {
      select(versions.value[0] ?? null);
    }
  } catch {
    versions.value = [];
    // 失败提示已由接口层统一处理
  } finally {
    loading.value = false;
  }
}

function select(item: StrategyVersion | null): void {
  editing.value = false;
  draftSnapshot.value = null;
  selectedId.value = item?.id ?? '';
  if (item) {
    openFamilyOf(item.name);
  }
  void loadBinding();
}

function switchType(type: StrategyType): void {
  if (type === activeType.value) {
    return;
  }
  activeType.value = type;
  selectedId.value = '';
  editing.value = false;
  void router.replace({ path: '/knowledge/strategy', query: { type } });
  void loadVersions();
}

function toggleShowInactive(): void {
  showInactive.value = !showInactive.value;
  void loadVersions();
}

// ============================================================
// 复制 / 新建 / 保存
// ============================================================

function startCopy(): void {
  if (!selected.value) {
    return;
  }
  draft.name = selected.value.name;
  draft.version = nextVersion(selected.value.version);
  // 用当前配置作为起点（复制语义）
  draftSnapshot.value = selected.value.configSnapshot;
  editing.value = true;
  configKey.value = '';
  syncConfigObject();
}

function openCreate(): void {
  draft.name = '';
  draft.version = 'v1';
  draftSnapshot.value = '{}';
  selectedId.value = '';
  editing.value = true;
  configKey.value = '';
  syncConfigObject();
}

function cancelEdit(): void {
  editing.value = false;
  draftSnapshot.value = null;
  configKey.value = '';
  syncConfigObject();
}

/**
 * 保存为新版本。
 *
 * <p>源版本存在 → 走 `PUT`（后端语义即复制新行）；不存在（新建）→ 走 `POST`。
 * 两者都可能因唯一键冲突报 40001。
 */
async function saveVersion(): Promise<void> {
  const name = draft.name.trim();
  const version = draft.version.trim();
  if (!name || !version) {
    ElMessage.warning('策略名与版本号都不能为空');
    return;
  }
  // 注意：configObject 是 reactive 对象而不是 ref，取序列化输入时不要再写 .value
  const snapshot = JSON.stringify(configObject);
  saving.value = true;
  try {
    const saved = selected.value
      ? await updateStrategyVersion(selected.value.id, { name, version, configSnapshot: snapshot })
      : await addStrategyVersion({
          type: activeType.value,
          name,
          version,
          configSnapshot: snapshot,
        });

    ElMessage.success(`已生成 ${saved.name} ${saved.version}`);
    editing.value = false;
    draftSnapshot.value = null;
    await loadVersions();
    // 保存后定位到新版本，让用户立刻看到结果
    selectedId.value = saved.id;
    openFamilyOf(saved.name);
    configKey.value = '';
    syncConfigObject();
  } catch (error) {
    reportSaveError(error);
  } finally {
    saving.value = false;
  }
}

/** 保存失败：唯一键冲突给出可操作的提示，而不是抛原始报文 */
function reportSaveError(error: unknown): void {
  const code = extractCode(error);
  if (code === CODE_PARAM_INVALID) {
    ElMessage.error('同类型下已有同名同版本号，请改一个版本号');
    return;
  }
  // 其余错误由接口层统一提示，这里仅在未提示时兜底
  if (code === null) {
    ElMessage.error('保存失败，请稍后重试');
  }
}

/** 从接口层抛出的错误里取出后端业务码；取不到返回 null */
function extractCode(error: unknown): number | null {
  if (typeof error === 'object' && error !== null && 'code' in error) {
    const raw = (error as { code?: unknown }).code;
    return typeof raw === 'number' ? raw : null;
  }
  return null;
}

// ============================================================
// 启停 / 删除
// ============================================================

async function toggleActive(): Promise<void> {
  if (!selected.value) {
    return;
  }
  const target = selected.value;
  const disabling = isStrategyActive(target);
  const strategyName = `${target.name} ${target.version}`;
  const typeLabel = strategyTypeLabel(target.type);
  const confirmed = await ElMessageBox.confirm(
    disabling
      ? `确认停用${typeLabel}策略「${strategyName}」？停用后绑定该版本的知识库在触发相关环节时会因策略未启用而失败，可随时重新启用`
      : `确认启用${typeLabel}策略「${strategyName}」？启用后绑定该版本的知识库恢复可用`,
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
      await updateStrategyVersionDisable(target.id);
      ElMessage.success(`已停用 ${target.name} ${target.version}`);
    } else {
      await updateStrategyVersionEnable(target.id);
      ElMessage.success(`已启用 ${target.name} ${target.version}`);
    }
    await loadVersions();
    await loadBinding();
  } catch {
    // 失败提示已由接口层统一处理
  }
}

async function removeVersion(): Promise<void> {
  if (!selected.value) {
    return;
  }
  const target = selected.value;
  try {
    await ElMessageBox.confirm(
      `删除 ${target.name} ${target.version}？被知识库绑定的策略无法删除，可改用「停用」。`,
      '删除策略版本',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    );
  } catch {
    return; // 用户取消
  }
  try {
    await deleteStrategyVersion(target.id);
    ElMessage.success('已删除');
    selectedId.value = '';
    await loadVersions();
  } catch (error) {
    if (extractCode(error) === CODE_BOUND_FORBIDDEN) {
      ElMessage.warning('该策略已被知识库绑定，无法删除；请先解绑，或改用「停用」');
      return;
    }
    // 其余错误由接口层提示
  }
}

// ============================================================
// 知识库绑定
// ============================================================

/**
 * 加载知识库绑定：**一次请求**取回该策略类型下所有已绑定的库。
 *
 * <p>用批量接口 `GET /knowledge-base/strategy-bindings?strategyType=`：
 * 后端一条 SQL 返回全部绑定行（每行带 knowledgeBaseId）。
 *
 * <p>`knowledgeBases` 仍要拉一次：右栏的绑定列表需要**所有**库（包括未绑定的，
 * 它们要显示「绑定此策略」按钮），而批量接口只返回已绑定的。
 */
async function loadBinding(): Promise<void> {
  if (!bindingSupported.value || !selected.value) {
    boundKbIds.value = new Set();
    return;
  }
  bindingLoading.value = true;
  try {
    const kbPromise =
      knowledgeBases.value.length === 0
        ? getKnowledgeBasePage({ current: 1, size: LIST_SIZE }).then((page) =>
            page.records.map((item) => ({ id: item.id, name: item.name })),
          )
        : Promise.resolve(null);
    // 两个请求互不依赖，并发发出
    const [kbList, bindings] = await Promise.all([
      kbPromise,
      getStrategyBindings(activeType.value),
    ]);
    if (kbList) {
      knowledgeBases.value = kbList;
    }
    const target = selected.value.id;
    boundKbIds.value = new Set(
      bindings
        .filter((item) => item.strategyVersionId === target)
        .map((item) => item.knowledgeBaseId)
        .filter((id): id is string => Boolean(id)),
    );
  } catch {
    boundKbIds.value = new Set();
  } finally {
    bindingLoading.value = false;
  }
}

async function bind(knowledgeBaseId: string): Promise<void> {
  if (!selected.value) {
    return;
  }
  try {
    await updateStrategyBinding(knowledgeBaseId, activeType.value, selected.value.id);
    ElMessage.success('已绑定');
    await loadBinding();
  } catch {
    // 失败提示已由接口层统一处理
  }
}

async function unbind(knowledgeBaseId: string): Promise<void> {
  const kbName =
    knowledgeBases.value.find((kb) => kb.id === knowledgeBaseId)?.name ?? knowledgeBaseId;
  const confirmed = await ElMessageBox.confirm(
    `确认解绑知识库「${kbName}」与${strategyTypeLabel(activeType.value)}策略的绑定？解绑后该知识库没有该环节的策略，相关环节会拒绝触发`,
    '解绑确认',
    {
      type: 'warning',
      confirmButtonText: '解绑',
      cancelButtonText: '取消',
    },
  ).catch(() => false);
  if (!confirmed) {
    return;
  }
  try {
    await updateStrategyBinding(knowledgeBaseId, activeType.value, null);
    ElMessage.success('已解绑');
    await loadBinding();
  } catch {
    // 失败提示已由接口层统一处理
  }
}

onMounted(async () => {
  await loadVersions();
  if (!selectedId.value && versions.value.length > 0) {
    select(versions.value[0]);
  }
});
</script>

<style scoped lang="css">
/*
 * 页面骨架（.page / .page-head / .page-title / .page-desc / .page-actions / .page-panel）
 * 来自全局 styles/page-shell.css。
 *
 * 顶部用 .page-head，两栏工作台装进 .page-panel。
 */

/* 两栏：左栏定宽、右栏吃剩余，面板内部的两栏 */
.stg-body {
  display: grid;
  flex: 1;
  min-height: 0;
  grid-template-columns: 296px 1fr;
}

/* ---------------- 左栏 ---------------- */
.stg-rail {
  display: flex;
  min-height: 0;

  /*
   * overflow: hidden 不能省 —— 它是**网格项自动最小尺寸**的开关。
   *
   * 网格项的 min-height 默认是 auto，含义是"不小于内容高度"，于是内部的
   * .stg-rail-body 会把它撑到内容那么高（776px），超出面板（705px）被
   * overflow:hidden 裁掉 —— 内部滚动条不可达，且切 tab 时高度会被重算纠正。
   * overflow 非 visible 会把自动最小尺寸重置为 0，网格项
   * 才真正遵守轨道高度。
   */
  overflow: hidden;
  flex-direction: column;
  border-right: 1px solid var(--kb-line);
  background: var(--kb-bg-1);
}

.stg-rail-types {
  display: flex;
  gap: 2px;
  padding: 9px 9px 0;
}

.stg-type-btn {
  height: 26px;
  flex: 1;
  border: 1px solid transparent;
  border-radius: 8px;
  background: transparent;
  color: var(--kb-text-3);
  font-size: 12px;
  cursor: pointer;
}

.stg-type-btn:hover {
  color: var(--kb-text-1);
}

.stg-type-btn.is-on {
  border-color: rgb(52 211 153 / 32%);
  background: var(--kb-tint);
  color: var(--kb-primary);
  font-weight: 600;
}

.stg-rail-body {
  flex: 1;
  padding: 9px 7px 12px;
  overflow-y: auto;
}

.stg-rail-empty {
  padding: 14px 10px;
  color: var(--kb-text-3);
  font-size: 12px;
  line-height: 1.7;
}

.stg-family {
  margin-bottom: 2px;
}

.stg-family-head {
  display: flex;
  gap: 8px;
  align-items: center;
  width: 100%;
  padding: 8px 9px;
  border: none;
  border-radius: 9px;
  background: transparent;
  color: inherit;
  cursor: pointer;
}

.stg-family-head:hover {
  background: var(--kb-surface);
}

.stg-caret {
  width: 0;
  height: 0;
  flex: none;
  border-top: 4px solid transparent;
  border-bottom: 4px solid transparent;
  border-left: 5px solid var(--kb-text-3);
  transition: transform 0.15s;
}

.stg-caret.is-open {
  transform: rotate(90deg) translateX(1px);
}

.stg-family-name {
  flex: 1;
  overflow: hidden;
  color: var(--kb-text-2);
  font-size: 13px;
  font-weight: 600;
  text-align: left;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.stg-family-dots {
  display: flex;
  gap: 3px;
  align-items: center;
}

.stg-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--kb-line-strong);
}

.stg-dot.is-live {
  background: var(--kb-primary);
}

.stg-family-vers {
  padding: 1px 0 5px 19px;
}

.stg-ver {
  display: flex;
  gap: 9px;
  align-items: center;
  width: 100%;
  padding: 6px 9px;
  border: none;
  border-radius: 9px;
  background: transparent;
  color: inherit;
  cursor: pointer;
}

.stg-ver:hover {
  background: var(--kb-surface);
}

.stg-ver.is-on {
  background: var(--kb-tint);
}

.stg-ver-v {
  width: 26px;
  flex: none;
  color: var(--kb-text-3);
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
  font-size: 11px;
  text-align: left;
}

.stg-ver.is-on .stg-ver-v {
  color: var(--kb-primary);
  font-weight: 700;
}

.stg-ver-s {
  flex: 1;
  color: var(--kb-text-3);
  font-size: 11px;
  text-align: left;
}

.stg-ver.is-on .stg-ver-s {
  color: var(--kb-primary);
  opacity: 0.85;
}

.stg-ver.is-off .stg-ver-v,
.stg-ver.is-off .stg-ver-s {
  opacity: 0.5;
}

/* ---------------- 右栏 ---------------- */
.stg-pane {
  display: flex;
  min-width: 0;

  /* 同 .stg-rail：网格项的自动最小尺寸会被内容撑开，overflow 非 visible 才能重置为 0 */
  overflow: hidden;
  flex-direction: column;
}

.stg-pane-empty {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 6px;
  align-items: center;
  justify-content: center;
  color: var(--kb-text-3);
  font-size: 13px;
}

.stg-pane-empty-hint {
  color: var(--kb-text-3);
  font-size: 12px;
}

.stg-pane-top {
  padding: 15px 24px 0;
  border-bottom: 1px solid var(--kb-line);
}

.stg-pane-title {
  display: flex;
  gap: 10px;
  align-items: center;
}

.stg-pane-name {
  display: flex;
  gap: 9px;
  align-items: baseline;
  font-size: 17px;
  font-weight: 700;
}

.stg-pane-ver {
  color: var(--kb-text-3);
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
  font-size: 13px;
  font-weight: 500;
}

.stg-tag {
  display: inline-flex;
  gap: 5px;
  align-items: center;
  height: 20px;
  padding: 0 8px;
  border: 1px solid transparent;
  border-radius: 5px;
  font-size: 11px;
  font-weight: 500;
}

.stg-tag-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: currentcolor;
}

.stg-tag.is-on {
  border-color: rgb(52 211 153 / 30%);
  background: var(--kb-tint);
  color: var(--kb-primary);
}

.stg-tag.is-off {
  border-color: var(--kb-line-strong);
  color: var(--kb-text-3);
}

.stg-pane-ops {
  display: flex;
  gap: 7px;
}

.stg-pane-meta {
  display: flex;
  gap: 10px;
  align-items: center;
  padding: 10px 0 12px;
  color: var(--kb-text-3);
  font-size: 11px;
}

.stg-meta-sep {
  color: var(--kb-line-strong);
}

.stg-blood {
  display: flex;
  gap: 6px;
  align-items: center;
}

.stg-blood-label {
  color: var(--kb-text-3);
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.08em;
}

.stg-blood-node {
  padding: 1px 7px;
  border: 1px solid var(--kb-line-strong);
  border-radius: 999px;
  color: var(--kb-text-3);
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
  font-size: 11px;
}

.stg-blood-node.is-on {
  border-color: rgb(52 211 153 / 40%);
  background: var(--kb-tint);
  color: var(--kb-primary);
  font-weight: 600;
}

.stg-blood-node.is-next {
  border-style: dashed;
}

.stg-blood-arrow {
  color: var(--kb-text-3);
  font-size: 11px;
}

.stg-pane-scroll {
  flex: 1;
  padding: 18px 24px 20px;
  overflow: auto;

  /* 配置区不出现滚动条：内容超高时仍可滚，但不占位且不可见 */
  scrollbar-width: none;
}

.stg-sec {
  display: flex;
  gap: 8px;
  align-items: center;
  margin: 20px 0 4px;
}

.stg-sec:first-child {
  margin-top: 0;
}

.stg-sec-name {
  color: var(--kb-text-2);
  font-size: 12px;
  font-weight: 650;
  letter-spacing: 0.02em;
}

.stg-help {
  display: grid;
  width: 14px;
  height: 14px;
  border: 1px solid var(--kb-line-strong);
  border-radius: 50%;
  color: var(--kb-text-3);
  font-size: 9px;
  font-style: normal;
  cursor: help;
  place-items: center;
}

.stg-help:hover {
  border-color: var(--kb-primary);
  color: var(--kb-primary);
}

.stg-name-row {
  display: grid;
  gap: 14px;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  padding: 10px 0 4px;
}

.stg-field {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.stg-field-label {
  color: var(--kb-text-3);
  font-size: 12px;
}

.stg-mono-input {
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
}

.stg-warn {
  padding: 9px 12px;
  margin: 10px 0;
  border: 1px solid rgb(251 191 36 / 28%);
  border-radius: 10px;
  background: rgb(251 191 36 / 8%);
  color: var(--kb-warn);
  font-size: 12px;
  line-height: 1.6;
}

.stg-binds {
  display: flex;
  flex-direction: column;
  border-top: 1px solid var(--kb-line);
}

.stg-bind {
  display: flex;
  gap: 11px;
  align-items: center;
  min-height: 44px;
  border-bottom: 1px solid var(--kb-line);
}

.stg-bind-dot {
  width: 6px;
  height: 6px;
  flex: none;
  border-radius: 50%;
  background: var(--kb-line-strong);
}

.stg-bind-dot.is-on {
  background: var(--kb-primary);
}

.stg-bind-name {
  flex: 1;
  font-size: 13px;
}

.stg-bind-state {
  color: var(--kb-text-3);
  font-size: 11px;
}

.stg-bind-empty {
  padding: 12px 0;
  color: var(--kb-text-3);
  font-size: 12px;
}

.stg-mono {
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
}
</style>
