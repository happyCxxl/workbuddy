// 规范合规脚本：校验 SFC 块顺序、scoped 样式、:deep 禁用、路由激活类、未使用 CSS 类名、
// 字体栈通用回退、小数 px 与路由懒加载。
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import process from 'node:process';

const srcDir = join(process.cwd(), 'src');
const errors = [];
const GENERIC_FAMILIES = new Set([
  'serif',
  'sans-serif',
  'monospace',
  'cursive',
  'fantasy',
  'system-ui',
  'ui-serif',
  'ui-sans-serif',
  'ui-monospace',
  'ui-rounded',
  'math',
  'emoji',
  'fangsong',
]);
const ROUTER_ACTIVE_CLASSES = new Set(['router-link-active', 'router-link-exact-active']);

function walkVueFiles(dir) {
  const out = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) {
      out.push(...walkVueFiles(full));
    } else if (entry.name.endsWith('.vue')) {
      out.push(full);
    }
  }
  return out;
}

function collectTemplateClasses(template) {
  const classes = new Set();
  for (const match of template.matchAll(/class="([^"]*)"/g)) {
    for (const token of match[1].split(/\s+/)) {
      if (token) {
        classes.add(token);
      }
    }
  }
  for (const match of template.matchAll(/:class="([^"]*)"/g)) {
    for (const token of match[1].matchAll(/'([^']+)'/g)) {
      if (/^[a-z][a-z0-9-]*$/.test(token[1])) {
        classes.add(token[1]);
      }
    }
  }
  return classes;
}

// 取根 template 块的完整内容：块内可以再嵌 <template #slot>，
// 用 indexOf('</template>') 会在第一个插槽模板处截断，导致后面的类名全被误判为未使用
function extractRootTemplate(content) {
  const start = content.indexOf('<template');
  if (start === -1) {
    return '';
  }
  const openEnd = content.indexOf('>', start);
  if (openEnd === -1) {
    return '';
  }
  let depth = 1;
  let cursor = openEnd + 1;
  while (cursor < content.length) {
    const nextOpen = content.indexOf('<template', cursor);
    const nextClose = content.indexOf('</template>', cursor);
    if (nextClose === -1) {
      break;
    }
    if (nextOpen !== -1 && nextOpen < nextClose) {
      depth += 1;
      cursor = nextOpen + '<template'.length;
      continue;
    }
    depth -= 1;
    if (depth === 0) {
      return content.slice(openEnd + 1, nextClose);
    }
    cursor = nextClose + '</template>'.length;
  }
  return content.slice(openEnd + 1);
}

// 运行时类名：classList.add/remove/toggle/contains('x') 里的类名算「已使用」，
// 这些类名并入模板类名集合，不参与未使用类名检查
function collectScriptClasses(script) {
  const classes = new Set();
  for (const match of script.matchAll(/classList\.(?:add|remove|toggle|contains)\(\s*'([^']+)'/g)) {
    classes.add(match[1]);
  }
  return classes;
}

function checkSfc(rel, content) {
  const templateStart = content.indexOf('<template');
  const scriptStart = content.indexOf('<script');
  const styleMatch = content.match(/<style(\s[^>]*)?>/);
  const styleStart = styleMatch ? styleMatch.index : -1;
  if (templateStart === -1 || (scriptStart !== -1 && scriptStart < templateStart)) {
    errors.push(`${rel}: template 块必须位于最前（规范 §5.7）`);
  }
  if (styleStart !== -1) {
    if (styleStart < templateStart || (scriptStart !== -1 && styleStart < scriptStart)) {
      errors.push(`${rel}: style 块必须位于 script 之后（规范 §5.7）`);
    }
    if (!/<style\s[^>]*scoped/.test(styleMatch[0])) {
      errors.push(`${rel}: style 块必须带 scoped（规范 §5.5）`);
    }
  }
  return styleMatch;
}

function extractStyleBody(content) {
  const match = content.match(/<style[^>]*>([\s\S]*?)<\/style>/);
  return match ? match[1] : '';
}

function checkStyleRules(rel, css, templateClasses) {
  const clean = css.replace(/\/\*[\s\S]*?\*\//g, '');
  const used = new Set();
  for (const match of clean.matchAll(/([^{}]+)\{/g)) {
    const selector = match[1];
    if (selector.includes(':deep(')) {
      errors.push(`${rel}: 组件 scoped 样式禁用 :deep（Element Plus 定制放 styles/element-plus.css）`);
      continue;
    }
    for (const token of selector.matchAll(/\.([a-z0-9-]+)/g)) {
      const cls = token[1];
      if (ROUTER_ACTIVE_CLASSES.has(cls)) {
        errors.push(`${rel}: 样式类 .${cls} 属 vue-router 运行时类名，改用自管激活类绑定`);
        continue;
      }
      used.add(cls);
    }
  }
  for (const cls of used) {
    if (!templateClasses.has(cls)) {
      errors.push(`${rel}: 样式类 .${cls} 在模板中未使用（规范 §6.1）`);
    }
  }
}

function checkFontFamily(rel, css) {
  for (const match of css.matchAll(/font-family:\s*([^;]+);/g)) {
    const value = match[1].trim();
    if (value.includes('var(')) {
      errors.push(`${rel}: font-family 必须为字面量字体栈，禁止 var() 形式（IDEA 无法解析）`);
      continue;
    }
    const last = value.split(',').pop().trim().toLowerCase();
    if (!GENERIC_FAMILIES.has(last)) {
      errors.push(`${rel}: font-family 必须以通用字体族结尾（如 monospace / sans-serif）`);
    }
  }
}

function checkRouter() {
  const file = join(srcDir, 'router', 'index.ts');
  if (!existsSync(file)) {
    errors.push('router/index.ts: 路由文件缺失');
    return;
  }
  const text = readFileSync(file, 'utf8');
  for (const match of text.matchAll(/component:\s*([^,]+),/g)) {
    if (!/\(\)\s*=>\s*import\(/.test(match[1])) {
      errors.push('router/index.ts: 路由组件必须懒加载 () => import(...)（规范 §7.1）');
    }
  }
}

function checkStyles(dir) {
  const out = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (entry.isFile() && entry.name.endsWith('.css')) {
      out.push(join(dir, entry.name));
    }
  }
  return out;
}

function checkFractionalPx(files) {
  for (const file of files) {
    const text = readFileSync(file, 'utf8');
    const rel = file.slice(process.cwd().length + 1).replace(/\\/g, '/');
    const match = text.match(/\d+\.\d+px/);
    if (match) {
      errors.push(`${rel}: 检测到小数 px 值 ${match[0]}（规范 §6 取整，避免跨浏览器渲染差异）`);
    }
  }
}

const vueFiles = walkVueFiles(srcDir);
const cssFiles = checkStyles(join(srcDir, 'styles'));
for (const file of vueFiles) {
  const content = readFileSync(file, 'utf8');
  const rel = file.slice(srcDir.length + 1).replace(/\\/g, '/');
  const styleMatch = checkSfc(rel, content);
  if (styleMatch) {
    const css = extractStyleBody(content);
    const templateClasses = collectTemplateClasses(extractRootTemplate(content));
    const scriptStart = content.indexOf('<script');
    const scriptEnd = content.indexOf('</script>');
    if (scriptStart !== -1 && scriptEnd !== -1) {
      for (const cls of collectScriptClasses(content.slice(scriptStart, scriptEnd))) {
        templateClasses.add(cls);
      }
    }
    checkStyleRules(rel, css, templateClasses);
    checkFontFamily(rel, css);
  }
}
for (const file of cssFiles) {
  const rel = file.slice(srcDir.length + 1).replace(/\\/g, '/');
  checkFontFamily(rel, readFileSync(file, 'utf8'));
}
checkFractionalPx([...vueFiles, ...cssFiles]);
checkRouter();

if (errors.length > 0) {
  console.error(errors.join('\n'));
  process.exit(1);
}
console.log('check-spec: 通过');
