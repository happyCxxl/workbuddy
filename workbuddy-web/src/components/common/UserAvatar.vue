<template>
  <img
    v-if="url"
    class="user-avatar user-avatar-img"
    :class="{ 'user-avatar-clickable': clickable }"
    :src="url"
    alt=""
    @error="emit('error')"
  />
  <div v-else class="user-avatar" :class="{ 'user-avatar-clickable': clickable }">{{ text }}</div>
</template>

<script setup lang="ts">
// 用户头像：有图显示图（加载失败由父组件回退），无图显示姓名首字
withDefaults(
  defineProps<{
    /** 头像地址；为空时显示首字 */
    url: string | null;
    /** 姓名首字 */
    text: string;
    /** 是否作为可点击入口显示手型 */
    clickable?: boolean;
  }>(),
  { clickable: false },
);

const emit = defineEmits<{ error: [] }>();
</script>

<style scoped lang="css">
.user-avatar {
  display: grid;
  flex: none;
  width: 32px;
  height: 32px;
  place-items: center;
  border: 1px solid rgb(34 224 255 / 40%);
  border-radius: 50%;
  background: linear-gradient(135deg, rgb(34 224 255 / 30%), rgb(77 124 255 / 20%));
  color: var(--kb-primary);
  font-size: 13px;
}

.user-avatar-img {
  object-fit: cover;
}

.user-avatar-clickable {
  cursor: pointer;
}
</style>
