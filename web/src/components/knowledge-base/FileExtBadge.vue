<template>
  <span
    class="file-ext"
    :class="{ 'is-small': small }"
    :data-tone="tone"
    :data-on="selected ? '1' : null"
    :title="title"
    >{{ text }}</span
  >
</template>

<script setup lang="ts">
import { computed } from 'vue';

/**
 * 文件格式徽标。
 *
 * <p>按**格式家族**分色而不是按扩展名逐个配：格式全集有 10 种（后端 FileFormat），
 * 但 PPT 与 PPTX、XLS 与 XLSX 是同一族，配色按族走既好记又不会花。
 * 未知扩展名回落 `generic`（中性灰），不会出现没样式的裸徽标。
 *
 * <p>**色调走 `data-tone` 属性而不是动态类名**：样式检查脚本不认 `:class` 绑定的
 * 变量值（只认模板里的字面类名），拼 `tone-${...}` 会被判成"样式类未使用"。
 */
const props = defineProps<{
  /** 文件扩展名（如 PDF / DOCX / TXT） */
  ext: string;
  /** 所属文件行是否被选中：选中时徽标加发光描边 */
  selected: boolean;
  /** 小尺寸：文件栏收起后只剩一列图标时用 */
  small?: boolean;
  /** 原生 title 提示（收起态里没有文件名可看，靠它提示是哪个文件） */
  title?: string;
}>();

/** 扩展名 → 色调（大小写不敏感）；未列出的格式统一走 generic */
const EXT_TONES: Record<string, string> = {
  PDF: 'pdf',
  // 文档家族
  DOC: 'doc',
  DOCX: 'doc',
  // 表格家族
  XLS: 'sheet',
  XLSX: 'sheet',
  CSV: 'sheet',
  // 演示家族
  PPT: 'slide',
  PPTX: 'slide',
  // 纯文本家族
  TXT: 'text',
  MD: 'text',
  // 图片家族
  JPG: 'image',
  JPEG: 'image',
  PNG: 'image',
  TIFF: 'image',
  BMP: 'image',
  GIF: 'image',
  WEBP: 'image',
};

const text = computed(() => props.ext.toUpperCase());

const tone = computed(() => EXT_TONES[props.ext.toUpperCase()] ?? 'generic');
</script>

<style scoped lang="css">
/*
 * 徽标定宽 40px，四字扩展名（DOCX / PPTX）也放得下 ——
 * 宽度随文字变会让多行文件的名字起始位置参差，扫读时很乱。
 */
.file-ext {
  display: flex;
  width: 40px;
  height: 40px;
  flex: none;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--kb-line-strong);
  border-radius: 11px;
  background: linear-gradient(160deg, rgb(255 255 255 / 8%), rgb(255 255 255 / 2%));
  color: var(--kb-text-3);
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
  font-size: 10px;
  font-weight: 700;

  /* 四字扩展名靠轻微收字距挤进 40px，不缩字号 */
  letter-spacing: -0.03em;

  /*
   * 悬停：轻微放大，弹性缓动做出"q 弹"手感。**只缩放、不位移** ——
   * 缩放围绕自身中心，徽标在选中底（父级行上那层淡青绿底）里始终居中。
   *
   * <p>`--ext-hover` 由**父级行**在 hover 时置 1（父组件的 scoped 样式能选中徽标自身，
   * 自定义属性沿继承传下来）。这样鼠标停在整行任意处徽标都会弹，不必精确停在徽标上；
   * 也不必用 `:deep()`（项目全局禁用）。
   */
  transform: scale(calc(1 + var(--ext-hover, 0) * 0.06));
  transition:
    transform 0.18s cubic-bezier(0.34, 1.56, 0.64, 1),
    box-shadow 0.18s ease;
}

/* ==================== 各格式家族配色（按 data-tone 分派）==================== */

/* PDF：珊瑚红 */
.file-ext[data-tone='pdf'] {
  border-color: rgb(255 122 122 / 45%);
  background: linear-gradient(160deg, rgb(255 122 122 / 32%), rgb(255 122 122 / 12%));
  color: var(--kb-file-pdf);
  box-shadow: 0 0 14px rgb(255 122 122 / 16%);
}

/* Word：天蓝 */
.file-ext[data-tone='doc'] {
  border-color: rgb(96 165 250 / 45%);
  background: linear-gradient(160deg, rgb(96 165 250 / 32%), rgb(96 165 250 / 12%));
  color: var(--kb-file-doc);
  box-shadow: 0 0 14px rgb(96 165 250 / 16%);
}

/* Excel / CSV：薄荷绿 */
.file-ext[data-tone='sheet'] {
  border-color: rgb(74 222 128 / 45%);
  background: linear-gradient(160deg, rgb(74 222 128 / 32%), rgb(74 222 128 / 12%));
  color: var(--kb-file-sheet);
  box-shadow: 0 0 14px rgb(74 222 128 / 16%);
}

/* PowerPoint：琥珀橙 */
.file-ext[data-tone='slide'] {
  border-color: rgb(251 146 60 / 45%);
  background: linear-gradient(160deg, rgb(251 146 60 / 32%), rgb(251 146 60 / 12%));
  color: var(--kb-file-slide);
  box-shadow: 0 0 14px rgb(251 146 60 / 16%);
}

/* 纯文本 / Markdown：雾紫 */
.file-ext[data-tone='text'] {
  border-color: rgb(167 139 250 / 45%);
  background: linear-gradient(160deg, rgb(167 139 250 / 32%), rgb(167 139 250 / 12%));
  color: var(--kb-file-text);
  box-shadow: 0 0 14px rgb(167 139 250 / 16%);
}

/* 图片：青蓝 */
.file-ext[data-tone='image'] {
  border-color: rgb(34 211 238 / 45%);
  background: linear-gradient(160deg, rgb(34 211 238 / 32%), rgb(34 211 238 / 12%));
  color: var(--kb-file-image);
  box-shadow: 0 0 14px rgb(34 211 238 / 16%);
}

/* ==================== 小尺寸（文件栏收起后只剩图标）==================== */

.file-ext.is-small {
  width: 28px;
  height: 28px;
  border-radius: 8px;
  font-size: 9px;
  letter-spacing: -0.04em;
}

/* ==================== 交互态 ==================== */

/* 选中态：内描边 + 投影，与行的选中态呼应 */
.file-ext[data-on='1'] {
  box-shadow:
    0 0 0 1px rgb(255 255 255 / 24%) inset,
    0 3px 12px rgb(0 0 0 / 32%);
}
</style>
