<template>
  <div class="page">
    <div class="page-panel">
      <!-- 筛选栏：搜索与筛选集中一处，与表体、分页同属一个面板（工具栏样式来自 .page-toolbar） -->
      <div class="page-toolbar">
        <el-input
          v-model="keyword"
          class="user-search"
          placeholder="搜索用户名"
          clearable
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />
        <el-select v-model="roleFilter" class="user-select" placeholder="角色">
          <el-option label="全部角色" value="" />
          <el-option label="管理员" value="ADMIN" />
          <el-option label="普通用户" value="USER" />
        </el-select>
        <el-select v-model="statusFilter" class="user-select" placeholder="状态">
          <el-option label="全部状态" value="" />
          <el-option label="启用" :value="USER_STATUS.ENABLED" />
          <el-option label="停用" :value="USER_STATUS.DISABLED" />
        </el-select>
        <el-button class="page-btn-primary" @click="handleSearch">查询</el-button>
        <el-button class="page-btn-ghost" @click="handleReset">重置</el-button>
        <span class="page-spacer"></span>
        <el-button class="page-btn-primary" @click="openCreate">新增用户</el-button>
        <button
          class="page-refresh"
          type="button"
          title="刷新"
          :disabled="loading"
          @click="loadUsers"
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
        <span class="user-filter-count">共 {{ total }} 个账号</span>
      </div>

      <!-- 固定高度滚动区：高度由祖先链的确定高度决定，不随记录数变化；
           el-table 拿到确定高度后表头冻结、表体内部滚动 -->
      <div class="page-table-body">
        <!-- 列宽与对齐口径见 styles/page-shell.css 的「列表表格」一节：8 列同 min-width 即等宽 -->
        <el-table
          v-loading="loading"
          class="page-table"
          height="100%"
          :data="records"
          :border="false"
        >
          <el-table-column prop="username" label="用户名" min-width="150" align="center" />
          <el-table-column label="姓名" min-width="150" align="center">
            <template #default="{ row }">{{ row.displayName || '-' }}</template>
          </el-table-column>
          <el-table-column label="角色" min-width="150" align="center">
            <template #default="{ row }">
              <el-tag :type="row.role === 'ADMIN' ? 'warning' : 'info'" effect="plain" size="small">
                {{ row.role === 'ADMIN' ? '管理员' : '普通用户' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" min-width="150" align="center">
            <template #default="{ row }">
              <el-tag
                :type="row.status === USER_STATUS.ENABLED ? 'success' : 'info'"
                effect="dark"
                size="small"
              >
                {{ row.status === USER_STATUS.ENABLED ? '启用' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="创建人" min-width="150" align="center">
            <template #default="{ row }">{{ row.createBy || '-' }}</template>
          </el-table-column>
          <el-table-column label="创建时间" min-width="150" align="center">
            <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
          </el-table-column>
          <el-table-column label="更新时间" min-width="150" align="center">
            <template #default="{ row }">{{ formatTime(row.updateTime) }}</template>
          </el-table-column>
          <el-table-column label="操作" min-width="150" align="center" fixed="right">
            <!--
              插槽里的 row 是 el-table 的 DefaultRow（不是 any，也不能给插槽 props 收窄类型 —— 那违反参数逆变），
              交给 isSelf / openEdit / handleDelete 这几个强类型函数时逐个显式断言成 UserVO
              （表格的 :data 即 UserVO[]）。
            -->
            <template #default="{ row }">
              <el-button
                class="user-row-btn"
                link
                type="primary"
                :disabled="isSelf(row as UserVO)"
                :title="isSelf(row as UserVO) ? '不能编辑当前登录账号' : ''"
                @click="openEdit(row as UserVO)"
              >
                编辑
              </el-button>
              <el-button
                class="user-row-btn"
                link
                type="danger"
                :disabled="isSelf(row as UserVO)"
                :title="isSelf(row as UserVO) ? '不能删除当前登录账号' : ''"
                @click="handleDelete(row as UserVO)"
              >
                删除
              </el-button>
            </template>
          </el-table-column>
          <template #empty>
            <div class="page-table-empty">{{ loading ? '加载中…' : '暂无用户' }}</div>
          </template>
        </el-table>
      </div>

      <div class="page-panel-foot">
        <span>每页 {{ query.size }} 条</span>
        <el-pagination
          class="user-pager"
          layout="prev, pager, next"
          :total="total"
          :page-size="query.size"
          :current-page="query.current"
          @current-change="handlePageChange"
        />
      </div>
    </div>

    <!-- 新增 / 编辑：同一弹窗，编辑时用户名只读、密码留空表示不重置 -->
    <el-dialog
      v-model="dialogVisible"
      class="user-dialog"
      :title="isEdit ? '编辑用户' : '新增用户'"
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
        <el-form-item prop="username" label="用户名" class="user-dialog-item">
          <el-input
            v-model="dialogForm.username"
            class="login-input"
            placeholder="3-32 位用户名"
            :disabled="isEdit"
          />
        </el-form-item>
        <el-form-item prop="displayName" label="真实姓名" class="user-dialog-item">
          <el-input v-model="dialogForm.displayName" class="login-input" placeholder="真实姓名" />
        </el-form-item>
        <el-form-item prop="password" label="密码" class="user-dialog-item">
          <el-input
            v-model="dialogForm.password"
            class="login-input"
            type="password"
            show-password
            :placeholder="isEdit ? '留空表示不重置密码' : '8-10 位密码'"
          />
        </el-form-item>
        <el-form-item prop="confirmPassword" label="确认密码" class="user-dialog-item">
          <el-input
            v-model="dialogForm.confirmPassword"
            class="login-input"
            type="password"
            show-password
            :placeholder="isEdit ? '留空表示不重置密码' : '请再次输入密码'"
          />
        </el-form-item>
        <div class="user-dialog-pair">
          <el-form-item prop="email" label="邮箱" class="user-dialog-item">
            <el-input v-model="dialogForm.email" class="login-input" placeholder="选填" />
          </el-form-item>
          <el-form-item prop="phone" label="手机号" class="user-dialog-item">
            <el-input v-model="dialogForm.phone" class="login-input" placeholder="选填" />
          </el-form-item>
        </div>
        <el-form-item prop="role" label="角色" class="user-dialog-item">
          <el-select v-model="dialogForm.role" class="user-dialog-select" :disabled="isEditSelf">
            <el-option label="普通用户" value="USER" />
            <el-option label="管理员" value="ADMIN" />
          </el-select>
        </el-form-item>
        <el-form-item prop="status" label="状态" class="user-dialog-item">
          <el-radio-group v-model="dialogForm.status" :disabled="isEditSelf">
            <el-radio :value="USER_STATUS.ENABLED">启用</el-radio>
            <el-radio :value="USER_STATUS.DISABLED">停用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button class="page-btn-ghost" @click="dialogVisible = false">取消</el-button>
        <el-button class="page-btn-primary" :loading="submitting" @click="handleSubmit">
          保存
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus';
import { computed, onMounted, reactive, ref } from 'vue';

import { addUserByAdmin, deleteUser, getUserPage, updateUser } from '@/api/user';
import { useAuthStore } from '@/stores/auth';
import { USER_STATUS, type UserVO } from '@/types/user';

// 用户管理页：分页查询 + 新增/编辑/删除（接口层已统一处理失败提示，页面只处理成功分支）
const authStore = useAuthStore();

const query = reactive({ current: 1, size: 10 });

// 筛选条件：空串表示「全部」，请求时统一转成 undefined（不传该查询参数）
const roleFilter = ref<string>('');
const statusFilter = ref<number | string>('');

const keyword = ref('');
const records = ref<UserVO[]>([]);
const total = ref(0);
const loading = ref(false);

const dialogVisible = ref(false);
const submitting = ref(false);
const editingId = ref<string | null>(null);
const dialogFormRef = ref<FormInstance>();

const dialogForm = reactive({
  username: '',
  displayName: '',
  email: '',
  phone: '',
  password: '',
  confirmPassword: '',
  role: 'USER',
  status: USER_STATUS.ENABLED,
});

const isEdit = computed(() => editingId.value !== null);
// 后端同样拒绝改自己的角色/状态，这里提前禁用输入
const isEditSelf = computed(() => isEdit.value && editingId.value === authStore.userId);

const dialogRules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 32, message: '用户名长度须为 3-32 位', trigger: 'blur' },
  ],
  displayName: [
    { required: true, message: '请输入真实姓名', trigger: 'blur' },
    { max: 64, message: '真实姓名不能超过 64 位', trigger: 'blur' },
  ],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }],
  phone: [{ pattern: /^[0-9+()\- ]{6,32}$/, message: '手机号格式不正确', trigger: 'blur' }],
  password: [
    {
      validator: (_rule, value: string, callback: (error?: Error) => void) => {
        if (!isEdit.value && !value) {
          callback(new Error('请输入密码'));
          return;
        }
        if (value && (value.length < 8 || value.length > 64)) {
          callback(new Error('密码长度须为 8-10 位'));
          return;
        }
        callback();
      },
      trigger: 'blur',
    },
  ],
  confirmPassword: [
    {
      validator: (_rule, value: string, callback: (error?: Error) => void) => {
        if (value !== dialogForm.password) {
          callback(new Error('两次输入的密码不一致'));
          return;
        }
        callback();
      },
      trigger: 'blur',
    },
  ],
};

/** 时间展示：后端下发 ISO 字符串，这里统一按 yyyy-MM-dd HH:mm 呈现 */
function formatTime(value: string | null): string {
  if (!value) {
    return '-';
  }
  return value.replace('T', ' ').slice(0, 16);
}

/** 是否为当前登录账号：后端禁止操作自身，前端提前禁用按钮 */
function isSelf(row: UserVO): boolean {
  return authStore.userId !== null && row.id === authStore.userId;
}

async function loadUsers(): Promise<void> {
  loading.value = true;
  try {
    const page = await getUserPage({
      current: query.current,
      size: query.size,
      username: keyword.value.trim() || undefined,
      role: roleFilter.value === '' ? undefined : roleFilter.value,
      status: statusFilter.value === '' ? undefined : Number(statusFilter.value),
    });
    records.value = page.records;
    total.value = page.total;
  } catch {
    // 失败提示已由接口层统一拦截处理
  } finally {
    loading.value = false;
  }
}

/** 查询条件变化后回到第一页，避免停留在越界页码 */
function handleSearch(): void {
  query.current = 1;
  void loadUsers();
}

/** 重置全部筛选条件并重新查询 */
function handleReset(): void {
  keyword.value = '';
  roleFilter.value = '';
  statusFilter.value = '';
  handleSearch();
}

function handlePageChange(current: number): void {
  query.current = current;
  void loadUsers();
}

function resetDialogForm(): void {
  dialogForm.username = '';
  dialogForm.displayName = '';
  dialogForm.email = '';
  dialogForm.phone = '';
  dialogForm.password = '';
  dialogForm.confirmPassword = '';
  dialogForm.role = 'USER';
  dialogForm.status = USER_STATUS.ENABLED;
}

function openCreate(): void {
  editingId.value = null;
  resetDialogForm();
  dialogVisible.value = true;
}

function openEdit(row: UserVO): void {
  editingId.value = row.id;
  dialogForm.username = row.username;
  dialogForm.displayName = row.displayName ?? '';
  dialogForm.email = row.email ?? '';
  dialogForm.phone = row.phone ?? '';
  dialogForm.password = '';
  dialogForm.confirmPassword = '';
  dialogForm.role = row.role === 'ADMIN' ? 'ADMIN' : 'USER';
  dialogForm.status = row.status;
  dialogVisible.value = true;
}

function handleDialogClosed(): void {
  editingId.value = null;
  resetDialogForm();
}

async function handleSubmit(): Promise<void> {
  const valid = await dialogFormRef.value?.validate().catch(() => false);
  if (!valid) {
    return;
  }
  submitting.value = true;
  try {
    if (editingId.value === null) {
      await addUserByAdmin({
        username: dialogForm.username,
        displayName: dialogForm.displayName,
        email: dialogForm.email,
        phone: dialogForm.phone,
        password: dialogForm.password,
        role: dialogForm.role,
        status: dialogForm.status,
      });
      ElMessage.success('新增成功');
    } else {
      await updateUser(editingId.value, {
        displayName: dialogForm.displayName,
        // 邮箱与手机号传空串表示清空，传 undefined 会被后端当成「不变」
        email: dialogForm.email,
        phone: dialogForm.phone,
        // 留空表示不重置密码，不把空串发给后端
        password: dialogForm.password || undefined,
        role: dialogForm.role,
        status: dialogForm.status,
      });
      ElMessage.success('更新成功');
    }
    dialogVisible.value = false;
    await loadUsers();
  } catch {
    // 失败提示已由接口层统一拦截处理
  } finally {
    submitting.value = false;
  }
}

async function handleDelete(row: UserVO): Promise<void> {
  const confirmed = await ElMessageBox.confirm(
    `确认删除用户「${row.username}」？删除后该账号无法登录。`,
    '删除确认',
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
  ).catch(() => false);
  if (!confirmed) {
    return;
  }
  try {
    await deleteUser(row.id);
    ElMessage.success('删除成功');
    // 删掉当前页最后一条时回退一页，避免停留在空列表（必须在重载前调页）
    if (records.value.length === 1 && query.current > 1) {
      query.current -= 1;
    }
    await loadUsers();
  } catch {
    // 失败提示已由接口层统一拦截处理
  }
}

onMounted(() => {
  void loadUsers();
});
</script>

<style scoped lang="css">
/*
 * 页面骨架全部来自全局 styles/page-shell.css（根元素直接用 .page，含 gap/高度约束，
 * 不在这里重写 —— 重写会出现"外层高度不定 + 内层面板要 flex:1"的矛盾）。
 * 本页只保留业务样式。
 */

/*
 * 筛选栏内容：搜索 + 角色 + 状态 + 查询/重置 + 刷新 + 计数，装在共用 .page-toolbar 里。
 * 按钮（.page-btn-primary / .page-btn-ghost）与刷新（.page-refresh）也在骨架层。
 */
.user-search {
  width: 200px;
}

.user-select {
  width: 130px;
}

.user-filter-count {
  margin-left: auto;
  color: var(--kb-text-3);
  font-size: 12px;
}

.user-row-btn {
  padding: 0 6px;
}

.user-dialog-select {
  width: 100%;
}

/* 邮箱与手机号并排：弹窗字段较多，成对排布以控制高度 */
.user-dialog-pair {
  display: flex;
  gap: 12px;
}

.user-dialog-pair > .user-dialog-item {
  flex: 1;
  min-width: 0;
}
</style>
