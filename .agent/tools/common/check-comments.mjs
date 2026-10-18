#!/usr/bin/env node
/**
 * 注释口径检查：注释只写"做什么"（职责与行为），不写"谁写的 / 为什么 / 从哪来 / 阶段 X"。
 *
 * <p>只扫注释文本（Java/TS/Vue/CSS 的行注释、块注释、HTML 注释与 Markdown 正文），
 * 不扫字符串字面量 —— 用户可见文案由评审覆盖（`--strings` 可做只读报告）。
 * 某行确实需要保留禁词时，在该行写 `口径豁免：<理由>`。
 *
 * 用法：
 *   node .agent/tools/common/check-comments.mjs              # 全量（代码 + 文档），命中 ERROR 即退出 1
 *   node .agent/tools/common/check-comments.mjs --strings    # 额外报告字符串字面量里的阶段词（不判失败）
 */

import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const SKIP_DIRS = new Set(['node_modules', 'target', 'dist', 'build', '.git', '.idea', '.vscode', 'logs', 'volumes', '.cache', '.gradle']);
const CODE_EXT = new Set(['.java', '.ts', '.vue', '.css', '.mjs', '.js']);
const DOC_EXT = new Set(['.md']);
/** 无扩展名的 shell 钩子脚本（注释以 # 开头） */
const SHELL_FILES = ['.husky/pre-commit', '.husky/pre-push'];
const EXEMPT = '口径豁免';

/** ERROR：违反"只写做什么"，必须改 */
const ERROR_WORDS = [
  '一期', '二期', '首期', '本阶段',
  '原先', '此前', '原来', '旧口径', '重构前',
  '因为', '之所以', '刻意', '免得', '以免', '便于',
  '供前端', '给前端', '让前端',
  '拍板', '用户明确选定', '用户口径',
  '为什么'
];

/** WARN：多数要改，但个别是行为契约或领域语义，报告不判失败 */
const WARN_WORDS = ['调用方', '调用侧', '调用点', '否则', '为了', '留给', '故意', '所以', '因此'];

/** 领域/规范语义：命中即跳过（令牌签发时间；规范条款本身必须写出被禁的维度名） */
const DOMAIN_EXEMPT = [/此前签发/, /原来如此/, /注释只写/, /不写谁写的/];

/** 文档里不再保留的沿革小节（用户拍板：文档只看现状、不留沿革） */
const FORBIDDEN_SECTIONS = [/^#{1,6}\s*变更记录/, /^#{1,6}\s*决策记录/];

const STRING_HINTS = ['一期', '二期', '首期'];

function walk(dir, out = []) {
  let entries;
  try {
    entries = readdirSync(dir);
  } catch {
    return out;
  }
  for (const name of entries) {
    if (SKIP_DIRS.has(name)) {
      continue;
    }
    const full = join(dir, name);
    let st;
    try {
      st = statSync(full);
    } catch {
      continue;
    }
    if (st.isDirectory()) {
      walk(full, out);
    } else if (st.isFile() && (CODE_EXT.has(name.slice(name.lastIndexOf('.'))) || DOC_EXT.has(name.slice(name.lastIndexOf('.'))))) {
      out.push(full);
    }
  }
  return out;
}

/** 抽注释文本：按行返回 [{line, text}]，text 为该行的注释内容（不含代码） */
function commentLines(file, content) {
  const ext = file.slice(file.lastIndexOf('.'));
  const lines = content.split(/\r?\n/);
  const result = [];
  if (ext === '.md') {
    lines.forEach((text, i) => result.push({ line: i + 1, text }));
    return result;
  }
  if (ext === '.sh' || file.endsWith('pre-commit') || file.endsWith('pre-push')) {
    lines.forEach((raw, i) => {
      const trimmed = raw.trim();
      if (trimmed.startsWith('#') && !trimmed.startsWith('#!')) {
        result.push({ line: i + 1, text: trimmed.replace(/^#+\s?/, '') });
      }
    });
    return result;
  }
  let inBlock = false;
  let inHtml = false;
  lines.forEach((raw, i) => {
    const line = i + 1;
    const parts = [];
    let rest = raw;
    if (inBlock || inHtml) {
      const closer = inBlock ? '*/' : '-->';
      const end = rest.indexOf(closer);
      if (end === -1) {
        parts.push(rest);
        result.push({ line, text: parts.join(' ') });
        return;
      }
      parts.push(rest.slice(0, end));
      rest = rest.slice(end + closer.length);
      inBlock = false;
      inHtml = false;
    }
    const lineComment = rest.indexOf('//');
    const blockStart = rest.indexOf('/*');
    if (lineComment !== -1 && (blockStart === -1 || lineComment < blockStart)) {
      parts.push(rest.slice(lineComment + 2));
    } else if (blockStart !== -1) {
      const end = rest.indexOf('*/', blockStart + 2);
      if (end === -1) {
        inBlock = true;
        parts.push(rest.slice(blockStart + 2));
      } else {
        parts.push(rest.slice(blockStart + 2, end));
        const after = rest.slice(end + 2);
        const html = after.indexOf('<!--');
        if (html !== -1) {
          const htmlEnd = after.indexOf('-->', html + 4);
          if (htmlEnd === -1) {
            inHtml = true;
            parts.push(after.slice(html + 4));
          } else {
            parts.push(after.slice(html + 4, htmlEnd));
          }
        }
      }
    }
    const html = rest.indexOf('<!--');
    if (html !== -1 && !inHtml) {
      const htmlEnd = rest.indexOf('-->', html + 4);
      if (htmlEnd === -1) {
        inHtml = true;
        parts.push(rest.slice(html + 4));
      } else {
        parts.push(rest.slice(html + 4, htmlEnd));
      }
    }
    if (parts.length > 0) {
      result.push({ line, text: parts.join(' ') });
    }
  });
  return result;
}

/** 抽字符串字面量（只读报告用） */
function stringLines(content) {
  const result = [];
  content.split(/\r?\n/).forEach((raw, i) => {
    const matches = raw.match(/"([^"\\]|\\.)*"/g);
    if (matches) {
      result.push({ line: i + 1, text: matches.join(' ') });
    }
  });
  return result;
}

const reportStrings = process.argv.includes('--strings');
// 通用约定：扫描整个仓库（按扩展名过滤、跳过依赖与构建目录），不写死任何项目目录。
const files = [
  ...walk(ROOT),
  ...SHELL_FILES.filter((rel) => existsSync(join(ROOT, rel))).map((rel) => join(ROOT, rel)),
];

const errors = [];
const warns = [];
const stringHits = [];
for (const file of files) {
  const rel = relative(ROOT, file).split(sep).join('/');
  if (rel === '.agent/tools/common/check-comments.mjs') {
    continue;
  }
  const content = readFileSync(file, 'utf8');
  if (rel.startsWith('docs/') || rel.startsWith('web/docs/') || rel.startsWith('.agent/')) {
    for (const { line, text } of commentLines(file, content)) {
      if (text.includes(EXEMPT)) {
        continue;
      }
      for (const pattern of FORBIDDEN_SECTIONS) {
        if (pattern.test(text)) {
          errors.push(`${rel}:${line}: 文档不再保留沿革小节「${text.trim()}」`);
        }
      }
    }
  }
  for (const { line, text } of commentLines(file, content)) {
    if (text.includes(EXEMPT) || DOMAIN_EXEMPT.some((pattern) => pattern.test(text))) {
      continue;
    }
    for (const word of ERROR_WORDS) {
      if (text.includes(word)) {
        errors.push(`${rel}:${line}: 「${word}」 ${text.trim().slice(0, 90)}`);
      }
    }
    for (const word of WARN_WORDS) {
      if (text.includes(word) && !(word === '调用方' && text.includes('调用方向'))) {
        warns.push(`${rel}:${line}: 「${word}」 ${text.trim().slice(0, 90)}`);
      }
    }
  }
  if (reportStrings) {
    for (const { line, text } of stringLines(content)) {
      for (const word of STRING_HINTS) {
        if (text.includes(word)) {
          stringHits.push(`${rel}:${line}: 「${word}」 ${text.trim().slice(0, 90)}`);
        }
      }
    }
  }
}

const print = (title, list) => {
  if (list.length === 0) {
    console.log(`${title}: 0`);
    return;
  }
  console.log(`${title}: ${list.length}`);
  for (const item of list) {
    console.log(`  ${item}`);
  }
};

print('ERROR（注释写了为什么/目的/给谁用/沿革/阶段）', errors);
print('WARN（调用方契约、否则、为了 —— 逐条判断）', warns);
if (reportStrings) {
  print('字符串里的阶段词（只读报告，不判失败）', stringHits);
}
console.log(`\n扫描文件 ${files.length} 个；ERROR ${errors.length}、WARN ${warns.length}。`);
process.exit(errors.length === 0 ? 0 : 1);
