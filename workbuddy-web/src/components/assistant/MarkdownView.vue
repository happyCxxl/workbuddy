<template>
  <div ref="bodyRef" class="md-body"></div>
</template>

<script setup lang="ts">
import '@/styles/markdown.css';

import DOMPurify from 'dompurify';
import katex from 'katex';
import 'katex/dist/katex.min.css';
import { marked } from 'marked';
import { createHighlighter, type Highlighter } from 'shiki';
import { nextTick, onMounted, ref, watch } from 'vue';

/** Markdown 渲染：marked → DOMPurify 消毒 → 代码块 Shiki 高亮 + 复制、公式 KaTeX（对齐 DSH 渲染链路）。 */

const props = defineProps<{ content: string }>();

const bodyRef = ref<HTMLDivElement | null>(null);
const LANGS = [
  'typescript',
  'javascript',
  'python',
  'java',
  'bash',
  'json',
  'sql',
  'vue',
  'html',
  'css',
  'xml',
  'yaml',
  'markdown',
  'diff',
];
let highlighterPromise: Promise<Highlighter> | null = null;

/** 代码块外渲染公式：代码段原样保留，避免 $ 被误当公式。 */
function renderMath(markdown: string): string {
  const parts = markdown.split(/(```[\s\S]*?```|`[^`]*`)/g);
  return parts
    .map((part, index) => {
      if (index % 2 === 1) {
        return part;
      }
      const block = part.replace(/\$\$([\s\S]+?)\$\$/g, (_match, expr: string) =>
        katex.renderToString(expr.trim(), { displayMode: true, throwOnError: false }),
      );
      return block.replace(/\$([^$\n]+?)\$/g, (_match, expr: string) =>
        katex.renderToString(expr.trim(), { displayMode: false, throwOnError: false }),
      );
    })
    .join('');
}

function getHighlighter(): Promise<Highlighter> {
  highlighterPromise ??= createHighlighter({ themes: ['github-dark'], langs: LANGS });
  return highlighterPromise;
}

/** 代码块加工：加语言标签与复制按钮，用 Shiki 高亮替换原始 pre。 */
async function highlightCode(): Promise<void> {
  const root = bodyRef.value;
  if (!root) {
    return;
  }
  const blocks = Array.from(root.querySelectorAll<HTMLElement>('pre > code'));
  if (blocks.length === 0) {
    return;
  }
  const highlighter = await getHighlighter();
  const loaded = new Set(highlighter.getLoadedLanguages());
  await Promise.all(
    blocks.map(async (code) => {
      const pre = code.parentElement;
      if (!pre || pre.dataset.hl === '1') {
        return;
      }
      const source = code.textContent;
      const langClass = Array.from(code.classList).find((item) => item.startsWith('language-'));
      const lang = langClass ? langClass.slice('language-'.length) : 'text';
      let highlighted: string;
      try {
        highlighted = highlighter.codeToHtml(source, {
          lang: loaded.has(lang) ? lang : 'text',
          theme: 'github-dark',
        });
      } catch {
        return;
      }
      pre.dataset.hl = '1';
      const wrapper = document.createElement('div');
      wrapper.classList.add('md-code');
      const head = document.createElement('div');
      head.classList.add('md-code-head');
      const label = document.createElement('span');
      label.classList.add('md-code-lang');
      label.textContent = lang;
      const copy = document.createElement('button');
      copy.type = 'button';
      copy.classList.add('md-code-copy');
      copy.textContent = '复制';
      copy.addEventListener('click', () => {
        void navigator.clipboard.writeText(source).then(() => {
          copy.textContent = '已复制';
          window.setTimeout(() => {
            copy.textContent = '复制';
          }, 1200);
        });
      });
      head.append(label, copy);
      const body = document.createElement('div');
      body.classList.add('md-code-body');
      body.innerHTML = highlighted;
      wrapper.append(head, body);
      pre.replaceWith(wrapper);
    }),
  );
}

async function render(): Promise<void> {
  const root = bodyRef.value;
  if (!root) {
    return;
  }
  const raw = marked.parse(renderMath(props.content), { gfm: true, breaks: true }) as string;
  root.innerHTML = DOMPurify.sanitize(raw);
  // 给渲染出的标签打 data-md：样式只用属性选择器，避免全局标签选择器污染
  root.querySelectorAll<HTMLElement>('*').forEach((element) => {
    element.setAttribute('data-md', element.tagName.toLowerCase());
  });
  await nextTick();
  await highlightCode();
}

watch(
  () => props.content,
  () => void render(),
  { immediate: true },
);
onMounted(() => void render());
</script>
