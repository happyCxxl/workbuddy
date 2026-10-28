<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h1 class="page-title">人设</h1>
        <p class="page-desc">
          人格（可乐的皮）：八字段可编辑；保存生成新版本并自动启用，已建会话保持绑定时的版本。
        </p>
      </div>
    </div>

    <div class="persona-wrap">
      <!-- 左：版本列表 -->
      <div class="persona-panel persona-versions">
        <div class="persona-head">
          <span class="persona-title">版本</span>
          <button class="page-refresh" type="button" title="刷新" @click="loadVersions">⟳</button>
        </div>
        <div class="version-list">
          <button
            v-for="item in versions"
            :key="item.version"
            type="button"
            class="version-item"
            :class="{ 'version-item-on': item.version === selectedVersion }"
            @click="selectVersion(item.version)"
          >
            <span class="version-main">
              <b>v{{ item.version }}</b>
              <el-tag v-if="item.active" type="success" effect="plain" size="small">启用中</el-tag>
            </span>
            <span class="version-time">{{ formatDateTime(item.created_at) }}</span>
          </button>
          <p v-if="versions.length === 0" class="version-empty">还没有人格版本。</p>
        </div>
      </div>

      <!-- 右：选中版本内容（可编辑后另存新版本） -->
      <div class="persona-panel persona-editor">
        <div class="persona-head">
          <span class="persona-title">
            v{{ selectedVersion }} · {{ selectedActive ? '启用中' : '未启用' }}
          </span>
          <span class="page-spacer"></span>
          <el-button v-if="!selectedActive" size="small" @click="handleActivate">
            启用此版本
          </el-button>
        </div>
        <div v-loading="loading" class="persona-body">
          <div class="persona-grid">
            <el-form-item label="名字">
              <el-input v-model="form.name" />
            </el-form-item>
            <el-form-item label="用户称呼">
              <el-input v-model="form.user_name" />
            </el-form-item>
            <el-form-item label="开场白">
              <el-input v-model="form.opening" />
            </el-form-item>
            <el-form-item label="语气">
              <el-input v-model="form.tone" />
            </el-form-item>
            <el-form-item label="口癖 / 口头禅（顿号分隔）">
              <el-input v-model="catchphrasesText" placeholder="明白、收到、好嘞" />
            </el-form-item>
            <el-form-item label="回应风格">
              <el-input v-model="form.style" />
            </el-form-item>
            <el-form-item class="persona-full" label="边界">
              <el-input v-model="form.boundaries" />
            </el-form-item>
            <el-form-item class="persona-full" label="用户画像">
              <el-input v-model="form.user_profile" type="textarea" :rows="3" resize="none" />
            </el-form-item>
          </div>
          <p class="persona-note">
            🔒 四条硬底线内置不可编辑：不争论 / 隐私不入记忆 / 风险先确认 / 不虚构能力。
          </p>
        </div>
        <div class="persona-foot">
          <el-button type="primary" :loading="saving" @click="handleSave">
            基于 v{{ selectedVersion }} 保存为新版本
          </el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ElMessage } from 'element-plus';
import { computed, onMounted, reactive, ref } from 'vue';

import {
  addPersonaVersion,
  getPersonaVersion,
  getPersonaVersions,
  updatePersonaActive,
} from '@/api/assistant';
import type { PersonaFields, PersonaVersionItem } from '@/types/assistant';
import { formatDateTime } from '@/utils/date';

const EMPTY_FIELDS: PersonaFields = {
  name: '',
  opening: '',
  catchphrases: [],
  tone: '',
  style: '',
  boundaries: '',
  user_name: '',
  user_profile: '',
};

const versions = ref<PersonaVersionItem[]>([]);
const selectedVersion = ref<number>(0);
const loading = ref(false);
const saving = ref(false);
const form = reactive<PersonaFields>({ ...EMPTY_FIELDS });
const catchphrasesText = ref('');

/** 选中版本是否启用中 */
const selectedActive = computed(() => {
  const item = versions.value.find((row) => row.version === selectedVersion.value);
  return item?.active ?? false;
});

/** 载入版本列表：默认选中启用版本 */
async function loadVersions(): Promise<void> {
  loading.value = true;
  try {
    versions.value = await getPersonaVersions();
    const active = versions.value.find((row) => row.active) ?? versions.value.at(0);
    if (active !== undefined) {
      await selectVersion(active.version);
    }
  } finally {
    loading.value = false;
  }
}

/** 选择版本：把字段填进表单（编辑即另存） */
async function selectVersion(version: number): Promise<void> {
  selectedVersion.value = version;
  const detail = await getPersonaVersion(version);
  Object.assign(form, detail.fields);
  catchphrasesText.value = detail.fields.catchphrases.join('、');
}

/** 保存为新版本（自动启用） */
async function handleSave(): Promise<void> {
  saving.value = true;
  try {
    const fields: PersonaFields = {
      ...form,
      catchphrases: catchphrasesText.value
        .split('、')
        .map((part) => part.trim())
        .filter((part) => part.length > 0),
    };
    const version = await addPersonaVersion(fields);
    ElMessage.success(`已保存为 v${version} 并启用`);
    await loadVersions();
  } finally {
    saving.value = false;
  }
}

/** 启用选中版本：只影响新会话 */
async function handleActivate(): Promise<void> {
  await updatePersonaActive(selectedVersion.value);
  ElMessage.success(`已启用 v${selectedVersion.value}（只影响新会话）`);
  await loadVersions();
}

onMounted(loadVersions);
</script>

<style scoped lang="css">
.persona-wrap {
  display: grid;
  flex: 1;
  min-height: 0;
  grid-template-columns: 260px minmax(0, 1fr);
  gap: 14px;
}

.persona-panel {
  display: flex;
  min-height: 0;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--kb-line);
  border-radius: var(--kb-radius);
  background: var(--kb-bg-2);
}

.persona-head {
  display: flex;
  flex: none;
  gap: 10px;
  align-items: center;
  padding: 12px 14px;
  border-bottom: 1px solid var(--kb-line);
}

.persona-title {
  font-size: 13px;
  font-weight: 600;
}

.version-list {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 8px;
}

.version-item {
  display: flex;
  width: 100%;
  flex-direction: column;
  gap: 4px;
  padding: 10px 12px;
  border: 1px solid transparent;
  border-radius: 10px;
  background: none;
  color: var(--kb-text-1);
  cursor: pointer;
  font-family: 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif;
  text-align: left;
}

.version-item-on {
  border-color: rgb(52 211 153 / 25%);
  background: var(--kb-tint);
}

.version-main {
  display: flex;
  gap: 8px;
  align-items: center;
  font-family: ui-monospace, Consolas, monospace;
}

.version-time {
  color: var(--kb-text-3);
  font-size: 11px;
}

.version-empty {
  padding: 16px 12px;
  color: var(--kb-text-3);
  font-size: 12px;
  text-align: center;
}

.persona-body {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 16px 18px;
}

.persona-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 18px;
}

.persona-full {
  grid-column: 1 / -1;
}

.persona-note {
  margin: 4px 0 0;
  color: var(--kb-text-3);
  font-size: 12px;
}

.persona-foot {
  display: flex;
  flex: none;
  gap: 10px;
  padding: 12px 18px 16px;
  border-top: 1px solid var(--kb-line);
}
</style>
