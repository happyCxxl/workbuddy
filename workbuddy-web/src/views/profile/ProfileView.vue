<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h1 class="page-title">个人中心</h1>
        <p class="page-desc">账号信息、联系方式与登录密码</p>
      </div>
    </div>

    <div class="page-panel">
      <div class="profile-body">
        <section class="profile-hero">
          <img
            v-if="avatarUrl"
            class="profile-avatar profile-avatar-img"
            :src="avatarUrl"
            alt=""
            @error="onAvatarError"
          />
          <div v-else class="profile-avatar">{{ avatarText }}</div>
          <div class="profile-hero-name">{{ displayNameLabel }}</div>
          <div class="profile-hero-tags">
            <span class="profile-tag">{{ roleLabel }}</span>
            <span class="profile-status" :class="statusOn ? 'is-on' : 'is-off'">
              <i class="profile-dot"></i>{{ statusLabel }}
            </span>
          </div>
          <div class="profile-actions">
            <input
              ref="avatarInputRef"
              class="profile-file"
              type="file"
              accept="image/png,image/jpeg,image/webp"
              @change="handleAvatarChange"
            />
            <el-button :loading="savingAvatar" @click="pickAvatar">更换头像</el-button>
            <el-button v-if="avatarUrl" @click="handleRemoveAvatar">移除</el-button>
          </div>
          <div class="profile-chips">
            <span
              v-for="item in completeness"
              :key="item.label"
              class="profile-chip"
              :class="item.done ? 'is-ok' : 'is-todo'"
            >
              {{ item.label }}{{ item.done ? ' ✓' : ' 待填' }}
            </span>
          </div>

          <dl class="profile-facts">
            <div class="profile-kv">
              <dt class="profile-kv-key">用户名</dt>
              <dd class="profile-kv-value">{{ profile?.username ?? '-' }}</dd>
            </div>
            <div class="profile-kv">
              <dt class="profile-kv-key">创建时间</dt>
              <dd class="profile-kv-value">{{ createdLabel }}</dd>
            </div>
            <div class="profile-kv">
              <dt class="profile-kv-key">更新时间</dt>
              <dd class="profile-kv-value">{{ updatedLabel }}</dd>
            </div>
          </dl>

          <div class="profile-hero-foot">
            <el-button class="profile-logout" @click="handleLogout">退出登录</el-button>
          </div>
        </section>

        <div class="profile-cards">
          <section class="profile-card">
            <h2 class="profile-card-title">基本资料</h2>
            <el-form
              ref="infoFormRef"
              class="profile-form"
              :model="infoForm"
              :rules="infoRules"
              label-position="top"
            >
              <el-form-item prop="displayName" label="真实姓名">
                <el-input v-model="infoForm.displayName" placeholder="真实姓名" />
              </el-form-item>
              <el-form-item prop="email" label="邮箱">
                <el-input v-model="infoForm.email" placeholder="选填" autocomplete="email" />
              </el-form-item>
              <el-form-item prop="phone" label="手机号">
                <el-input v-model="infoForm.phone" placeholder="选填" autocomplete="tel" />
              </el-form-item>
              <el-button class="profile-save" :loading="savingInfo" @click="handleSaveInfo">
                保存
              </el-button>
            </el-form>
          </section>

          <section class="profile-card">
            <h2 class="profile-card-title">修改密码</h2>
            <el-form
              ref="passwordFormRef"
              class="profile-form"
              :model="passwordForm"
              :rules="passwordRules"
              label-position="top"
            >
              <el-form-item prop="oldPassword" label="当前密码">
                <el-input
                  v-model="passwordForm.oldPassword"
                  type="password"
                  show-password
                  autocomplete="current-password"
                  placeholder="当前密码"
                />
              </el-form-item>
              <el-form-item prop="newPassword" label="新密码">
                <el-input
                  v-model="passwordForm.newPassword"
                  type="password"
                  show-password
                  autocomplete="new-password"
                  placeholder="8-10 位密码"
                />
              </el-form-item>
              <el-form-item prop="confirmPassword" label="确认新密码">
                <el-input
                  v-model="passwordForm.confirmPassword"
                  type="password"
                  show-password
                  autocomplete="new-password"
                  placeholder="再次输入新密码"
                />
              </el-form-item>
              <el-button class="profile-save" :loading="savingPassword" @click="handleSavePassword">
                保存
              </el-button>
            </el-form>
          </section>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ElMessage, type FormInstance, type FormRules } from 'element-plus';
import { computed, onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';

import { deleteAvatar, getProfile, updateAvatar, updatePassword, updateProfile } from '@/api/user';
import { useAuthStore } from '@/stores/auth';
import { USER_STATUS, type UserVO } from '@/types/user';
import { formatDateTime } from '@/utils/date';

// 个人中心：横幅放身份摘要，下面两栏分别放资料与密码；两块表单各自独立提交
const authStore = useAuthStore();
const router = useRouter();

const profile = ref<UserVO | null>(null);

const ROLE_LABELS: Record<string, string> = { ADMIN: '管理员', USER: '普通用户' };

const displayNameLabel = computed(
  () => profile.value?.displayName || authStore.displayName || authStore.username || '未登录',
);
const avatarText = computed(() => displayNameLabel.value.slice(0, 1).toUpperCase());

// 头像：接口带回的是可直接渲染的地址；未设置、或图挂了都回落姓名首字
const avatarInputRef = ref<HTMLInputElement>();
const savingAvatar = ref(false);
const avatarFailed = ref(false);
const avatarUrl = computed(() => (avatarFailed.value ? null : authStore.avatar));

function onAvatarError(): void {
  avatarFailed.value = true;
}

function pickAvatar(): void {
  avatarInputRef.value?.click();
}

async function handleAvatarChange(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  // 清空选择：同一个文件连选两次也要能再次触发 change
  input.value = '';
  if (!file) {
    return;
  }
  savingAvatar.value = true;
  try {
    const url = await updateAvatar(file);
    avatarFailed.value = false;
    authStore.setAvatar(url);
    ElMessage.success('头像已更新');
  } catch {
    // 失败提示已由接口层统一拦截处理
  } finally {
    savingAvatar.value = false;
  }
}

async function handleRemoveAvatar(): Promise<void> {
  savingAvatar.value = true;
  try {
    await deleteAvatar();
    avatarFailed.value = false;
    authStore.setAvatar(null);
    ElMessage.success('头像已移除');
  } catch {
    // 失败提示已由接口层统一拦截处理
  } finally {
    savingAvatar.value = false;
  }
}
const roleLabel = computed(() => ROLE_LABELS[profile.value?.role ?? ''] ?? '普通用户');

const statusLabel = computed(() =>
  profile.value?.status === USER_STATUS.ENABLED ? '启用' : '停用',
);
const statusOn = computed(() => profile.value?.status === USER_STATUS.ENABLED);
const createdLabel = computed(() =>
  profile.value?.createTime ? formatDateTime(profile.value.createTime) : '-',
);
const updatedLabel = computed(() =>
  profile.value?.updateTime ? formatDateTime(profile.value.updateTime) : '-',
);

/** 资料完善度：三项都填了才算齐 */
const completeness = computed(() => [
  { label: '姓名', done: Boolean(profile.value?.displayName) },
  { label: '邮箱', done: Boolean(profile.value?.email) },
  { label: '手机号', done: Boolean(profile.value?.phone) },
]);

const infoFormRef = ref<FormInstance>();
const savingInfo = ref(false);

const infoForm = reactive({
  displayName: '',
  email: '',
  phone: '',
});

const infoRules: FormRules = {
  displayName: [
    { required: true, message: '请输入真实姓名', trigger: 'blur' },
    { max: 64, message: '真实姓名不能超过 64 位', trigger: 'blur' },
  ],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }],
  phone: [{ pattern: /^[0-9+()\- ]{6,32}$/, message: '手机号格式不正确', trigger: 'blur' }],
};

const passwordFormRef = ref<FormInstance>();
const savingPassword = ref(false);

const passwordForm = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: '',
});

const passwordRules: FormRules = {
  oldPassword: [{ required: true, message: '请输入当前密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 8, max: 10, message: '密码长度须为 8-10 位', trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback: (error?: Error) => void) => {
        if (value !== passwordForm.newPassword) {
          callback(new Error('两次输入的密码不一致'));
          return;
        }
        callback();
      },
      trigger: 'blur',
    },
  ],
};

// 进页面取一次服务端资料：本地快照可能不是最新的
async function loadProfile(): Promise<void> {
  infoFormRef.value?.clearValidate();
  try {
    const data = await getProfile();
    profile.value = data;
    infoForm.displayName = data.displayName ?? '';
    infoForm.email = data.email ?? '';
    infoForm.phone = data.phone ?? '';
  } catch {
    // 失败提示已由接口层统一拦截处理
  }
}

async function handleSaveInfo(): Promise<void> {
  if (savingInfo.value) {
    return;
  }
  const valid = await infoFormRef.value?.validate().catch(() => false);
  if (!valid) {
    return;
  }
  savingInfo.value = true;
  try {
    await updateProfile({
      displayName: infoForm.displayName,
      email: infoForm.email,
      phone: infoForm.phone,
    });
    // 同步布局壳与本页的展示，不必重新登录；顺带刷新横幅里的更新时间与完善度
    authStore.setDisplayName(infoForm.displayName);
    ElMessage.success('保存成功');
    await loadProfile();
  } catch {
    // 失败提示已由接口层统一拦截处理
  } finally {
    savingInfo.value = false;
  }
}

async function handleSavePassword(): Promise<void> {
  if (savingPassword.value) {
    return;
  }
  const valid = await passwordFormRef.value?.validate().catch(() => false);
  if (!valid) {
    return;
  }
  savingPassword.value = true;
  try {
    const token = await updatePassword({
      oldPassword: passwordForm.oldPassword,
      newPassword: passwordForm.newPassword,
    });
    // 服务端已递增令牌版本，本地令牌必须换成响应里补签的那张
    authStore.replaceToken(token);
    passwordForm.oldPassword = '';
    passwordForm.newPassword = '';
    passwordForm.confirmPassword = '';
    passwordFormRef.value?.clearValidate();
    ElMessage.success('密码已修改，其他设备需用新密码重新登录');
  } catch {
    // 失败提示已由接口层统一拦截处理
  } finally {
    savingPassword.value = false;
  }
}

// 退出登录：项目口径为客户端丢弃令牌（无登出接口），随后回到登录页
function handleLogout(): void {
  authStore.clearToken();
  ElMessage.success('已退出登录');
  void router.push('/login');
}

onMounted(loadProfile);
</script>

<style scoped lang="css">
/*
 * 两栏：左侧身份卡固定 360px，右侧两块表单并排。
 * 面板本身不滚动，两栏按可用高度分配，内容紧凑排布。
 */
.profile-body {
  display: grid;
  flex: 1;
  min-height: 0;
  gap: 16px;
  grid-template-columns: 360px minmax(0, 1fr);
  align-content: center;
  padding: 20px 22px;
}

/* 身份卡：极光渐变 + 大点头像，中心对齐 */
.profile-hero {
  display: flex;
  flex-direction: column;
  min-height: 0;
  gap: 10px;
  align-items: center;
  overflow: hidden;
  padding: 22px 20px;
  border: 1px solid rgb(255 255 255 / 10%);
  border-radius: var(--kb-radius);
  background:
    radial-gradient(120% 120% at 12% 0%, rgb(52 211 153 / 20%), transparent 55%),
    radial-gradient(90% 120% at 92% 8%, rgb(45 212 191 / 14%), transparent 60%),
    linear-gradient(180deg, rgb(255 255 255 / 6%), rgb(255 255 255 / 2%));
  box-shadow:
    0 1px 0 rgb(255 255 255 / 8%) inset,
    0 18px 40px rgb(0 0 0 / 38%);
}

.profile-avatar {
  display: grid;
  width: 118px;
  height: 118px;
  flex: none;
  place-items: center;
  border: 2px solid rgb(255 255 255 / 18%);
  border-radius: 50%;
  background: linear-gradient(135deg, rgb(52 211 153 / 35%), rgb(45 212 191 / 22%));
  box-shadow: 0 10px 26px rgb(52 211 153 / 18%);
  color: var(--kb-primary);
  font-size: 44px;
}

/* 图片头像：按方形裁切填满圆框，不设会拉变形 */
.profile-avatar-img {
  object-fit: cover;
}

.profile-hero-name {
  margin-top: 2px;
  font-size: 20px;
  font-weight: 650;
}

.profile-hero-tags {
  display: flex;
  gap: 10px;
  align-items: center;
}

.profile-tag {
  padding: 3px 10px;
  border: 1px solid rgb(52 211 153 / 35%);
  border-radius: 999px;
  background: var(--kb-tint);
  color: var(--kb-primary);
  font-size: 12px;
}

/* 状态：小圆点 + 文字，比角色标签更轻，一眼看出账号是否可用 */
.profile-status {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  color: var(--kb-text-2);
  font-size: 12px;
}

.profile-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--kb-text-3);
}

.profile-status.is-on .profile-dot {
  background: var(--kb-primary);
  box-shadow: 0 0 0 3px rgb(52 211 153 / 14%);
}

.profile-status.is-off .profile-dot {
  background: var(--kb-danger);
  box-shadow: 0 0 0 3px rgb(248 113 113 / 14%);
}

.profile-actions {
  display: flex;
  gap: 8px;
}

/* 文件选择框只作为「更换头像」的触发源，不直接展示 */
.profile-file {
  display: none;
}

.profile-chips {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: center;
}

.profile-chip {
  padding: 4px 10px;
  border: 1px solid var(--kb-line);
  border-radius: 999px;
  font-size: 12px;
}

.profile-chip.is-ok {
  border-color: rgb(163 230 53 / 35%);
  color: var(--kb-ok);
}

.profile-chip.is-todo {
  border-color: rgb(251 191 36 / 35%);
  color: var(--kb-warn);
}

/* 只读事实：与表单区分开，用虚线分隔更像「档案」 */
.profile-facts {
  width: 100%;
  margin: 8px 0 0;
  padding-top: 6px;
  border-top: 1px solid var(--kb-line);
}

.profile-kv {
  display: flex;
  justify-content: space-between;
  padding: 7px 0;
  border-bottom: 1px dashed rgb(255 255 255 / 6%);
  font-size: 13px;
}

.profile-kv:last-child {
  border-bottom: 0;
}

.profile-kv-key {
  color: var(--kb-text-3);
}

.profile-kv-value {
  margin: 0;
}

.profile-hero-foot {
  margin-top: auto;
  padding-top: 12px;
}

.profile-logout {
  border-color: rgb(248 113 113 / 35%);
  color: var(--kb-danger);
}

.profile-cards {
  display: grid;
  min-height: 0;
  gap: 16px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.profile-card {
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
  padding: 18px 20px;
  border: 1px solid rgb(255 255 255 / 10%);
  border-radius: var(--kb-radius);
  background: linear-gradient(180deg, rgb(255 255 255 / 6%), rgb(255 255 255 / 2%));
  box-shadow:
    0 1px 0 rgb(255 255 255 / 6%) inset,
    0 12px 30px rgb(0 0 0 / 32%);
}

/* 标题左侧的小色块：给每个卡片一个视觉锚点 */
.profile-card-title {
  margin: 0 0 14px;
  font-size: 15px;
  font-weight: 650;
}

.profile-card-title::before {
  content: '';
  display: inline-block;
  width: 6px;
  height: 6px;
  margin-right: 8px;
  border-radius: 2px;
  background: var(--kb-primary);
  box-shadow: 0 0 0 3px rgb(52 211 153 / 14%);
  vertical-align: middle;
}

.profile-form {
  max-width: 420px;
}

.profile-save {
  min-width: 84px;
  margin-top: auto;
}
</style>
