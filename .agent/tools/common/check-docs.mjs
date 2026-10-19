#!/usr/bin/env node
// 文档检查：
// 1. Markdown 中不得出现「变更记录」「决策记录」小节；
// 2. 文档里引用的仓库路径必须真实存在——Markdown 链接目标与反引号里的路径都算。
//    解析基准：先按文档所在目录，再回退仓库根（正文常写根级路径，如 `.agent/standards/...`）。
// 通用约定：扫描仓库内受版本控制的 .md 文件；不依赖词表。
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import process from 'node:process';
import { ROOT, backtickPaths, trackedFiles } from '../lib.mjs';

const FORBIDDEN = /^#{1,6}\s*(变更记录|决策记录)\s*$/;
const LINK = /\[[^\]]*]\(([^)\s]+)(?:\s+"[^"]*")?\)/g;

const errors = [];
const files = trackedFiles().filter((f) => f.endsWith('.md'));

/** 引用的两种基准路径；调用方逐个 existsSync。 */
function basesOf(file, raw) {
  const cleaned = raw.replace(/#.*$/, '').replace(/[.,;:]+$/, '');
  if (!cleaned) return [];
  const base = dirname(join(ROOT, ...file.split('/')));
  return [join(base, ...cleaned.split('/')), join(ROOT, ...cleaned.split('/'))];
}

function existsAny(bases) {
  return bases.length > 0 && bases.some((p) => existsSync(p));
}

for (const file of files) {
  let lines;
  try {
    lines = readFileSync(join(ROOT, file), 'utf8').split(/\r?\n/);
  } catch {
    continue;
  }
  lines.forEach((line, i) => {
    if (FORBIDDEN.test(line.trim())) {
      errors.push(`${file}:${i + 1} [JAVA-DOC-009] 不得出现「变更记录」「决策记录」小节`);
    }
    for (const m of line.matchAll(LINK)) {
      const target = m[1];
      if (/^(https?:|mailto:|#|<)/.test(target)) continue;
      if (!existsAny(basesOf(file, target))) {
        errors.push(`${file}:${i + 1} [JAVA-DOC-010] 链接目标不存在：${target}`);
      }
    }
    // 反引号路径去掉 Markdown 链接后扫描，避免把外链标签当仓库路径；
    // 只认末段带扩展名的串：正文里 `chunking/impl/ChunkPipeline`、`/home` 这类包路径与 URL 片段不是文件指针。
    for (const p of backtickPaths(line, {
      requireSlash: true,
      requireExtension: true,
      skipElision: true,
      stripLinks: true,
    })) {
      if (!existsAny(basesOf(file, p))) {
        errors.push(`${file}:${i + 1} [JAVA-DOC-010] 引用的路径不存在：${p}`);
      }
    }
  });
}

if (errors.length === 0) {
  console.log(`文档检查：通过（${files.length} 个文件）`);
  process.exit(0);
}
for (const e of errors) console.log(`ERROR ${e}`);
console.log('');
console.log(`文档检查：ERROR ${errors.length}`);
process.exit(1);
